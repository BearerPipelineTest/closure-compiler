/*
 * Copyright 2004 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.AstFactory.type;
import static com.google.javascript.jscomp.JsMessageVisitor.MESSAGE_TREE_MALFORMED;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.javascript.jscomp.JsMessage.Part;
import com.google.javascript.jscomp.JsMessage.PlaceholderFormatException;
import com.google.javascript.jscomp.JsMessage.StringPart;
import com.google.javascript.jscomp.JsMessageVisitor.MalformedException;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Node.SideEffectFlags;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * ReplaceMessages replaces user-visible messages with alternatives. It uses Google specific
 * JsMessageVisitor implementation.
 */
@GwtIncompatible("JsMessage")
public final class ReplaceMessages {
  public static final DiagnosticType BUNDLE_DOES_NOT_HAVE_THE_MESSAGE =
      DiagnosticType.error(
          "JSC_BUNDLE_DOES_NOT_HAVE_THE_MESSAGE",
          "Message with id = {0} could not be found in replacement bundle");

  public static final DiagnosticType INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS =
      DiagnosticType.error(
          "JSC_INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS",
          "Alternate message ID={0} placeholders ({1}) differs from {2} placeholders ({3}).");

  private final AbstractCompiler compiler;
  private final MessageBundle bundle;
  private final JsMessage.Style style;
  private final boolean strictReplacement;
  private final AstFactory astFactory;

  ReplaceMessages(
      AbstractCompiler compiler,
      MessageBundle bundle,
      JsMessage.Style style,
      boolean strictReplacement) {
    this.compiler = compiler;
    this.astFactory = compiler.createAstFactory();
    this.bundle = bundle;
    this.style = style;
    this.strictReplacement = strictReplacement;
  }

  /**
   * When the returned pass is executed, the original `goog.getMsg()` etc. calls will be replaced
   * with a form that will survive unchanged through optimizations unless eliminated as unused.
   *
   * <p>After all optimizations are complete, the pass returned by `getReplacementCompletionPass()`.
   */
  public CompilerPass getMsgProtectionPass() {
    return new MsgProtectionPass();
  }

  class MsgProtectionPass extends JsMessageVisitor {

    public MsgProtectionPass() {
      super(ReplaceMessages.this.compiler, style, bundle.idGenerator());
    }

    @Override
    public void process(Node externs, Node root) {
      // Add externs declarations for the function names we use in our replacements.
      NodeUtil.createSynthesizedExternsSymbol(compiler, ReplaceMessagesConstants.DEFINE_MSG_CALLEE);
      NodeUtil.createSynthesizedExternsSymbol(
          compiler, ReplaceMessagesConstants.FALLBACK_MSG_CALLEE);

      // JsMessageVisitor.process() does the traversal that calls the processX() methods below.
      super.process(externs, root);
    }

    @Override
    protected void processJsMessage(JsMessage message, JsMessageDefinition definition) {
      try {
        final Node origValueNode = definition.getMessageNode();
        switch (origValueNode.getToken()) {
          case CALL:
            // This is the currently preferred form.
            // `MSG_A = goog.getMsg('hello, {$name}', {name: getName()}, {html: true})`
            protectGetMsgCall(origValueNode, message);
            break;
          case STRINGLIT:
          case ADD:
            // a legacy format
            // `MSG_A = 'abc' + 'def';`
            protectStringLiteralOrConcatMsg(origValueNode, message);
            break;
          case FUNCTION:
            protectLegacyFunctionMsg(origValueNode, message);
            break;
          default:
            throw new MalformedException(
                "Expected FUNCTION, STRING, ADD, or CALL node; found: " + origValueNode,
                origValueNode);
        }
      } catch (MalformedException e) {
        compiler.report(JSError.make(e.getNode(), MESSAGE_TREE_MALFORMED, e.getMessage()));
      }
    }

    private void protectGetMsgCall(Node callNode, JsMessage message) throws MalformedException {
      checkArgument(callNode.isCall(), callNode);
      // `goog.getMsg('message string', {<substitutions>}, {<options>})`
      final Node googGetMsg = callNode.getFirstChild();
      final Node originalMessageString = checkNotNull(googGetMsg.getNext());
      final Node placeholdersNode = originalMessageString.getNext();
      final Node optionsNode = placeholdersNode == null ? null : placeholdersNode.getNext();
      final MsgOptions msgOptions = getOptions(message, optionsNode);

      // Construct
      // `__jscomp_define_msg__({<msg properties>}, {<substitutions>})`
      final String protectionFunctionName = ReplaceMessagesConstants.DEFINE_MSG_CALLEE;
      final Node newCallee =
          createProtectionFunctionCallee(protectionFunctionName).srcref(googGetMsg);
      final Node msgPropertiesNode =
          createMsgPropertiesNode(message, msgOptions).srcrefTree(originalMessageString);
      Node newCallNode =
          astFactory.createCall(newCallee, type(callNode), msgPropertiesNode).srcref(callNode);
      // If the result of this call (the message) is unused, there is no reason for optimizations
      // to preserve it.
      newCallNode.setSideEffectFlags(SideEffectFlags.NO_SIDE_EFFECTS);
      if (placeholdersNode != null) {
        checkState(placeholdersNode.isObjectLit(), placeholdersNode);
        // put quotes around the keys so they won't get renamed.
        for (Node strKey = placeholdersNode.getFirstChild();
            strKey != null;
            strKey = strKey.getNext()) {
          checkState(strKey.isStringKey(), strKey);
          strKey.setQuotedString();
        }
        newCallNode.addChildToBack(placeholdersNode.detach());
      }
      callNode.replaceWith(newCallNode);
      compiler.reportChangeToEnclosingScope(newCallNode);
    }

    private Node createProtectionFunctionCallee(String protectionFunctionName) {
      final Node callee = astFactory.createNameWithUnknownType(protectionFunctionName);
      // The name is declared constant in the externs definition we created, so all references
      // to it must also be marked as constant for consistency's sake.
      callee.putBooleanProp(Node.IS_CONSTANT_NAME, true);
      return callee;
    }

    private void protectStringLiteralOrConcatMsg(Node valueNode, JsMessage message) {
      final Node msgProps = createMsgPropertiesNode(message, new MsgOptions());
      final Node newCallNode =
          astFactory
              .createCall(
                  createProtectionFunctionCallee(ReplaceMessagesConstants.DEFINE_MSG_CALLEE),
                  type(valueNode),
                  msgProps)
              .srcrefTreeIfMissing(valueNode);
      newCallNode.setSideEffectFlags(SideEffectFlags.NO_SIDE_EFFECTS);
      valueNode.replaceWith(newCallNode);
      compiler.reportChangeToEnclosingScope(newCallNode);
    }

    private void protectLegacyFunctionMsg(Node functionNode, JsMessage message) {
      // `MSG_X = function(name1, name2) { return expressionUsingName1AndName2; };`
      // NOTE: The JsMessageVisitor code will have examined the return value and constructed a
      // message string with placeholders matching the parameter names.
      checkArgument(functionNode.isFunction(), functionNode);
      final Node paramListNode = functionNode.getSecondChild();
      final Node origBlock = paramListNode.getNext();
      // NOTE: JsMessageVisitor would have thrown a MalformedException before reaching this point
      // if the function body were not a single return statement.
      final Node returnNode = origBlock.getOnlyChild();
      final Node origValueNode = returnNode.getOnlyChild();

      // Convert to
      // ```javascript
      // MSG_X = function(name1, name2) {
      //     return __jscomp_define_msg__({<msg properties>}, {<substitutions>});
      // };
      // ```
      final Node newCallee =
          createProtectionFunctionCallee(ReplaceMessagesConstants.DEFINE_MSG_CALLEE);
      final Node msgPropertiesNode = createMsgPropertiesNode(message, new MsgOptions());
      // Convert the parameter list into a simple placeholders object
      // ```javascript
      // {
      //   'name1': name1,
      //   'name2': name2
      // }
      // ```
      QuotedKeyObjectLitBuilder placeholderObjLlitBuilder = new QuotedKeyObjectLitBuilder();
      for (Node paramName = paramListNode.getFirstChild();
          paramName != null;
          paramName = paramName.getNext()) {
        // Just assert here, because JsMessageVisitor should have already reported it if the
        // parameter list were malformed.
        checkState(paramName.isName(), paramName);
        placeholderObjLlitBuilder.addNode(paramName.getString(), paramName.cloneNode());
      }
      final Node placeholderNode = placeholderObjLlitBuilder.build();
      final Node newValueNode =
          astFactory
              .createCall(newCallee, type(origValueNode), msgPropertiesNode, placeholderNode)
              .srcrefTreeIfMissing(origValueNode);
      origValueNode.replaceWith(newValueNode);
      compiler.reportChangeToChangeScope(functionNode);
    }

    @Override
    void processMessageFallback(Node callNode, JsMessage message1, JsMessage message2) {
      final Node originalCallee = checkNotNull(callNode.getFirstChild(), callNode);
      final Node fallbackCallee =
          createProtectionFunctionCallee(ReplaceMessagesConstants.FALLBACK_MSG_CALLEE)
              .srcref(originalCallee);

      final Node originalFirstArg = checkNotNull(originalCallee.getNext(), callNode);
      final Node firstMsgKey = astFactory.createString(message1.getKey()).srcref(originalFirstArg);

      final Node originalSecondArg = checkNotNull(originalFirstArg.getNext(), callNode);
      final Node secondMsgKey =
          astFactory.createString(message2.getKey()).srcref(originalSecondArg);

      // `__jscomp_msg_fallback__('MSG_ONE', MSG_ONE, 'MSG_TWO', MSG_TWO)`
      final Node newCallNode =
          astFactory
              .createCall(
                  fallbackCallee,
                  type(callNode),
                  firstMsgKey,
                  originalFirstArg.detach(),
                  secondMsgKey,
                  originalSecondArg.detach())
              .srcref(callNode);
      // If the result of this call (the message) is unused, there is no reason for optimizations
      // to preserve it.
      newCallNode.setSideEffectFlags(SideEffectFlags.NO_SIDE_EFFECTS);
      callNode.replaceWith(newCallNode);
      compiler.reportChangeToEnclosingScope(newCallNode);
    }
  }

  private Node createMsgPropertiesNode(JsMessage message, MsgOptions msgOptions) {
    QuotedKeyObjectLitBuilder msgPropsBuilder = new QuotedKeyObjectLitBuilder();
    msgPropsBuilder.addString("key", message.getKey());
    String altId = message.getAlternateId();
    if (altId != null) {
      msgPropsBuilder.addString("alt_id", altId);
    }
    final String meaning = message.getMeaning();
    if (meaning != null) {
      msgPropsBuilder.addString("meaning", meaning);
    }
    msgPropsBuilder.addString("msg_text", message.toString());
    if (msgOptions.escapeLessThan) {
      // Just being present is what records this option as true
      msgPropsBuilder.addString("escapeLessThan", "");
    }
    if (msgOptions.unescapeHtmlEntities) {
      // Just being present is what records this option as true
      msgPropsBuilder.addString("unescapeHtmlEntities", "");
    }
    return msgPropsBuilder.build();
  }

  private final class QuotedKeyObjectLitBuilder {
    // LinkedHashMap to keep the keys in the order we set them so our output is deterministic.
    private final LinkedHashMap<String, Node> keyToValueNodeMap = new LinkedHashMap<>();

    private QuotedKeyObjectLitBuilder addString(String key, String value) {
      return addNode(key, astFactory.createString(value));
    }

    @CanIgnoreReturnValue
    private QuotedKeyObjectLitBuilder addNode(String key, Node node) {
      checkState(!keyToValueNodeMap.containsKey(key), "repeated key: %s", key);
      keyToValueNodeMap.put(key, node);
      return this;
    }

    private Node build() {
      final Node result = astFactory.createObjectLit();
      for (Entry<String, Node> entry : keyToValueNodeMap.entrySet()) {
        result.addChildToBack(astFactory.createQuotedStringKey(entry.getKey(), entry.getValue()));
      }
      return result;
    }
  }
  /**
   * When the returned pass is executed, the protected messages created by `getMsgProtectionPass()`
   * will be replaced by the final message form read from the appropriate message bundle.
   */
  public CompilerPass getReplacementCompletionPass() {
    return new ReplacementCompletionPass();
  }

  class ReplacementCompletionPass implements CompilerPass {
    // Keep track of which messages actually got translated, so we know what do do when we
    // see a message fallback call.
    final Set<String> translatedMsgKeys = new HashSet<>();

    @Override
    public void process(Node externs, Node root) {
      // for each `__jscomp_define_msg__` call in post-order traversal
      // replace it with the appropriate expression
      NodeTraversal.traverse(
          compiler,
          root,
          new AbstractPostOrderCallback() {
            @Override
            public void visit(NodeTraversal t, Node n, Node parent) {
              ProtectedJsMessage protectedJsMessage =
                  ProtectedJsMessage.fromAstNode(n, bundle.idGenerator());
              if (protectedJsMessage != null) {
                visitMsgDefinition(protectedJsMessage);
              } else {
                ProtectedMsgFallback protectedMsgFallback = ProtectedMsgFallback.fromAstNode(n);
                if (protectedMsgFallback != null) {
                  visitMsgFallback(protectedMsgFallback);
                }
              }
            }
          });
    }

    void visitMsgDefinition(ProtectedJsMessage protectedJsMessage) {
      try {
        final JsMessage originalMsg = protectedJsMessage.jsMessage;
        final Node nodeToReplace = protectedJsMessage.definitionNode;
        final JsMessage translatedMsg =
            lookupMessage(protectedJsMessage.definitionNode, bundle, originalMsg);
        final JsMessage msgToUse;
        if (translatedMsg != null) {
          msgToUse = translatedMsg;
          // Remember that this one got translated in case it is used in a fallback.
          translatedMsgKeys.add(originalMsg.getKey());
        } else {
          if (strictReplacement) {
            compiler.report(
                JSError.make(nodeToReplace, BUNDLE_DOES_NOT_HAVE_THE_MESSAGE, originalMsg.getId()));
          }
          msgToUse = originalMsg;
        }
        final MsgOptions msgOptions = new MsgOptions();
        msgOptions.escapeLessThan = protectedJsMessage.escapeLessThan;
        msgOptions.unescapeHtmlEntities = protectedJsMessage.unescapeHtmlEntities;
        final Map<String, Node> placeholderMap =
            createPlaceholderNodeMap(protectedJsMessage.substitutionsNode);
        final ImmutableSet<String> placeholderNames = msgToUse.placeholders();
        if (placeholderMap.isEmpty() && !placeholderNames.isEmpty()) {
          throw new MalformedException(
              "Empty placeholder value map for a translated message with placeholders.",
              nodeToReplace);
        } else {
          for (String placeholderName : placeholderNames) {
            if (!placeholderMap.containsKey(placeholderName)) {
              throw new MalformedException(
                  "Unrecognized message placeholder referenced: " + placeholderName, nodeToReplace);
            }
          }
        }
        final Node finalMsgConstructionExpression =
            constructStringExprNode(
                mergeStringParts(msgToUse.getParts()), placeholderMap, msgOptions);
        finalMsgConstructionExpression.srcrefTreeIfMissing(nodeToReplace);
        nodeToReplace.replaceWith(finalMsgConstructionExpression);
        compiler.reportChangeToEnclosingScope(finalMsgConstructionExpression);
      } catch (MalformedException e) {
        compiler.report(JSError.make(e.getNode(), MESSAGE_TREE_MALFORMED, e.getMessage()));
      }
    }

    private void visitMsgFallback(ProtectedMsgFallback protectedMsgFallback) {
      final Node valueNodeToUse;
      if (translatedMsgKeys.contains(protectedMsgFallback.firstMsgKey)) {
        // Obviously use the first message, if it is translated.
        valueNodeToUse = protectedMsgFallback.firstMsgValue;
      } else if (translatedMsgKeys.contains(protectedMsgFallback.secondMsgKey)) {
        // Fallback to the second message if it has a translation.
        valueNodeToUse = protectedMsgFallback.secondMsgValue;
      } else {
        // If neither is translated, then use the first message as it is defined in the source code.
        valueNodeToUse = protectedMsgFallback.firstMsgValue;
      }
      valueNodeToUse.detach();
      protectedMsgFallback.callNode.replaceWith(valueNodeToUse);
      compiler.reportChangeToEnclosingScope(valueNodeToUse);
    }
  }

  private static class ProtectedMsgFallback {
    final Node callNode;
    final String firstMsgKey;
    final Node firstMsgValue;
    final String secondMsgKey;
    final Node secondMsgValue;

    ProtectedMsgFallback(
        Node callNode,
        String firstMsgKey,
        Node firstMsgValue,
        String secondMsgKey,
        Node secondMsgValue) {
      this.callNode = callNode;
      this.firstMsgKey = firstMsgKey;
      this.firstMsgValue = firstMsgValue;
      this.secondMsgKey = secondMsgKey;
      this.secondMsgValue = secondMsgValue;
    }

    @Nullable
    static ProtectedMsgFallback fromAstNode(Node n) {
      if (!n.isCall()) {
        return null;
      }
      final Node callee = n.getFirstChild();
      if (!callee.matchesName(ReplaceMessagesConstants.FALLBACK_MSG_CALLEE)) {
        return null;
      }
      checkState(n.hasXChildren(5), "bad message fallback call: %s", n);
      final Node firstMsgKeyNode = callee.getNext();
      final String firstMsgKey = firstMsgKeyNode.getString();
      final Node firstMsgValue = firstMsgKeyNode.getNext();
      final Node secondMsgKeyNode = firstMsgValue.getNext();
      final String secondMsgKey = secondMsgKeyNode.getString();
      final Node secondMsgValue = secondMsgKeyNode.getNext();
      return new ProtectedMsgFallback(n, firstMsgKey, firstMsgValue, secondMsgKey, secondMsgValue);
    }
  }

  @Nullable
  private JsMessage lookupMessage(Node callNode, MessageBundle bundle, JsMessage message) {
    JsMessage translatedMessage = bundle.getMessage(message.getId());
    if (translatedMessage != null) {
      return translatedMessage;
    }

    String alternateId = message.getAlternateId();
    if (alternateId == null) {
      return null;
    }

    JsMessage alternateMessage = bundle.getMessage(alternateId);
    if (alternateMessage != null) {
      // Validate that the alternate message is compatible with this message. Ideally we'd also
      // check meaning and description, but they're not populated by `MessageBundle.getMessage`.
      if (!Objects.equals(message.placeholders(), alternateMessage.placeholders())) {
        compiler.report(
            JSError.make(
                callNode,
                INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS,
                alternateId,
                String.valueOf(alternateMessage.placeholders()),
                message.getKey(),
                String.valueOf(message.placeholders())));
        return null;
      }
    }
    return alternateMessage;
  }

  /**
   * When the returned pass is executed, the original `goog.getMsg()` etc. calls will all be
   * replaced with the final message form read from the message bundle.
   *
   * <p>This is the original way of running this pass as a single operation.
   */
  public CompilerPass getFullReplacementPass() {
    return new FullReplacementPass();
  }

  class FullReplacementPass extends JsMessageVisitor {

    public FullReplacementPass() {
      super(ReplaceMessages.this.compiler, style, bundle.idGenerator());
    }

    @Override
    void processMessageFallback(Node callNode, JsMessage message1, JsMessage message2) {
      boolean isFirstMessageTranslated = (lookupMessage(callNode, bundle, message1) != null);
      boolean isSecondMessageTranslated = (lookupMessage(callNode, bundle, message2) != null);
      Node replacementNode =
          (isSecondMessageTranslated && !isFirstMessageTranslated)
              ? callNode.getChildAtIndex(2)
              : callNode.getSecondChild();
      callNode.replaceWith(replacementNode.detach());
      Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(replacementNode);
      if (changeScope != null) {
        compiler.reportChangeToChangeScope(changeScope);
      }
    }

    @Override
    protected void processJsMessage(JsMessage message, JsMessageDefinition definition) {
      // Get the replacement.
      Node callNode = definition.getMessageNode();
      JsMessage replacement = lookupMessage(callNode, bundle, message);
      if (replacement == null) {
        if (strictReplacement) {
          compiler.report(
              JSError.make(callNode, BUNDLE_DOES_NOT_HAVE_THE_MESSAGE, message.getId()));
          // Fallback to the default message
          return;
        } else {
          // In case if it is not a strict replacement we could leave original
          // message.
          replacement = message;
        }
      }

      // Replace the message.
      Node newValue;
      Node msgNode = definition.getMessageNode();
      try {
        newValue = getNewValueNode(replacement, msgNode);
      } catch (MalformedException e) {
        compiler.report(JSError.make(e.getNode(), MESSAGE_TREE_MALFORMED, e.getMessage()));
        newValue = msgNode;
      }

      if (newValue != msgNode) {
        newValue.srcrefTreeIfMissing(msgNode);
        msgNode.replaceWith(newValue);
        compiler.reportChangeToEnclosingScope(newValue);
      }
    }

    /**
     * Constructs a node representing a message's value, or, if possible, just modifies {@code
     * origValueNode} so that it accurately represents the message's value.
     *
     * @param message a message
     * @param origValueNode the message's original value node
     * @return a Node that can replace {@code origValueNode}
     * @throws MalformedException if the passed node's subtree structure is not as expected
     */
    private Node getNewValueNode(JsMessage message, Node origValueNode) throws MalformedException {
      switch (origValueNode.getToken()) {
        case FUNCTION:
          // The message is a function. Modify the function node.
          updateFunctionNode(message, origValueNode);
          return origValueNode;
        case STRINGLIT:
          // The message is a simple string. Modify the string node.
          String newString = message.toString();
          if (!origValueNode.getString().equals(newString)) {
            origValueNode.setString(newString);
            compiler.reportChangeToEnclosingScope(origValueNode);
          }
          return origValueNode;
        case ADD:
          // The message is a simple string. Create a string node.
          return astFactory.createString(message.toString());
        case CALL:
          // The message is a function call. Replace it with a string expression.
          return replaceCallNode(message, origValueNode);
        default:
          throw new MalformedException(
              "Expected FUNCTION, STRING, or ADD node; found: " + origValueNode.getToken(),
              origValueNode);
      }
    }

    /**
     * Updates the descendants of a FUNCTION node to represent a message's value.
     *
     * <p>The tree looks something like:
     *
     * <pre>
     * function
     *  |-- name
     *  |-- lp
     *  |   |-- name <arg1>
     *  |    -- name <arg2>
     *   -- block
     *      |
     *       --return
     *           |
     *            --add
     *               |-- string foo
     *                -- name <arg1>
     * </pre>
     *
     * @param message a message
     * @param functionNode the message's original FUNCTION value node
     * @throws MalformedException if the passed node's subtree structure is not as expected
     */
    private void updateFunctionNode(JsMessage message, Node functionNode)
        throws MalformedException {
      checkNode(functionNode, Token.FUNCTION);
      Node nameNode = functionNode.getFirstChild();
      checkNode(nameNode, Token.NAME);
      Node argListNode = nameNode.getNext();
      checkNode(argListNode, Token.PARAM_LIST);
      Node oldBlockNode = argListNode.getNext();
      checkNode(oldBlockNode, Token.BLOCK);

      Node valueNode = constructAddOrStringNode(message.getParts(), argListNode);
      Node newBlockNode = IR.block(astFactory.createReturn(valueNode));

      if (!newBlockNode.isEquivalentTo(
          oldBlockNode,
          /* compareType= */ false,
          /* recurse= */ true,
          /* jsDoc= */ false,
          /* sideEffect= */ false)) {
        newBlockNode.srcrefTreeIfMissing(oldBlockNode);
        oldBlockNode.replaceWith(newBlockNode);
        compiler.reportChangeToEnclosingScope(newBlockNode);
      }
    }

    /**
     * Creates a parse tree corresponding to the remaining message parts in an iteration. The result
     * will contain only STRING nodes, NAME nodes (corresponding to placeholder references), and/or
     * ADD nodes used to combine the other two types.
     *
     * @param parts the message parts
     * @param argListNode a PARAM_LIST node whose children are valid placeholder names
     * @return the root of the constructed parse tree
     * @throws MalformedException if {@code partsIterator} contains a placeholder reference that
     *     does not correspond to a valid argument in the arg list
     */
    private Node constructAddOrStringNode(ImmutableList<Part> parts, Node argListNode)
        throws MalformedException {
      if (parts.isEmpty()) {
        return astFactory.createString("");
      }

      Node resultNode = null;
      for (Part part : parts) {
        final Node partNode = constructLegacyFunctionMsgPart(argListNode, part);
        resultNode = resultNode == null ? partNode : astFactory.createAdd(resultNode, partNode);
      }
      return resultNode;
    }

    private Node constructLegacyFunctionMsgPart(Node argListNode, Part part)
        throws MalformedException {
      Node partNode = null;
      if (part.isPlaceholder()) {

        for (Node node = argListNode.getFirstChild(); node != null; node = node.getNext()) {
          if (node.isName()) {
            String arg = node.getString();

            // We ignore the case here because the transconsole only supports
            // uppercase placeholder names, but function arguments in JavaScript
            // code can have mixed case.
            if (Ascii.equalsIgnoreCase(arg, part.getPlaceholderName())) {
              partNode = IR.name(arg);
            }
          }
        }

        if (partNode == null) {
          throw new MalformedException(
              "Unrecognized message placeholder referenced: " + part.getPlaceholderName(),
              argListNode);
        }
      } else {
        // The part is just a string literal.
        partNode = astFactory.createString(part.toString());
      }
      return partNode;
    }

    /**
     * Replaces a CALL node with an inlined message value.
     *
     * <p>For input that that looks like this
     * <pre>
     *   goog.getMsg(
     *       'Hi {$userName}! Welcome to {$product}.',
     *       { 'userName': 'someUserName', 'product': getProductName() })
     * <pre>
     *
     * <p>We'd return:
     * <pre>
     *   'Hi ' + someUserName + '! Welcome to ' + 'getProductName()' + '.'
     * </pre>
     *
     * @param message  a message
     * @param callNode  the message's original CALL value node
     * @return a STRING node, or an ADD node that does string concatenation, if
     *   the message has one or more placeholders
     *
     * @throws MalformedException if the passed node's subtree structure is
     *   not as expected
     */
    private Node replaceCallNode(JsMessage message, Node callNode) throws MalformedException {
      checkNode(callNode, Token.CALL);
      // `goog.getMsg`
      Node getPropNode = callNode.getFirstChild();
      checkNode(getPropNode, Token.GETPROP);
      Node stringExprNode = getPropNode.getNext();
      checkStringExprNode(stringExprNode);
      // optional `{ key1: value, key2: value2 }` replacements
      Node objLitNode = stringExprNode.getNext();
      // optional replacement options, e.g. `{ html: true }`
      MsgOptions options = getOptions(message, objLitNode != null ? objLitNode.getNext() : null);

      Map<String, Node> placeholderMap = createPlaceholderNodeMap(objLitNode);
      final ImmutableSet<String> placeholderNames = message.placeholders();
      if (placeholderMap.isEmpty() && !placeholderNames.isEmpty()) {
        throw new MalformedException(
            "Empty placeholder value map for a translated message with placeholders.", callNode);
      } else {
        for (String placeholderName : placeholderNames) {
          if (!placeholderMap.containsKey(placeholderName)) {
            throw new MalformedException(
                "Unrecognized message placeholder referenced: " + placeholderName, callNode);
          }
        }
      }

      // Build the replacement tree.
      return constructStringExprNode(mergeStringParts(message.getParts()), placeholderMap, options);
    }
  }

  private Map<String, Node> createPlaceholderNodeMap(Node objLitNode) throws MalformedException {
    Map<String, Node> placeholderMap = new HashMap<>();
    if (objLitNode != null) {
      for (Node key = objLitNode.getFirstChild(); key != null; key = key.getNext()) {
        checkState(key.isStringKey(), key);
        String name = key.getString();
        boolean isKeyAlreadySeen = placeholderMap.put(name, key.getOnlyChild()) != null;
        if (isKeyAlreadySeen) {
          throw new MalformedException("Duplicate placeholder name", key);
        }
      }
    }
    return placeholderMap;
  }

  /**
   * Creates a parse tree corresponding to the remaining message parts in an iteration. The result
   * consists of one or more STRING nodes, placeholder replacement value nodes (which can be
   * arbitrary expressions), and ADD nodes.
   *
   * @param msgParts an iterator over message parts
   * @param placeholderMap map from placeholder names to value Nodes
   * @return the root of the constructed parse tree
   */
  private Node constructStringExprNode(
      List<Part> msgParts, Map<String, Node> placeholderMap, MsgOptions options) {

    if (msgParts.isEmpty()) {
      return astFactory.createString("");
    } else {
      Node resultNode = null;
      for (Part msgPart : msgParts) {
        final Node partNode = createNodeForMsgPart(msgPart, options, placeholderMap);
        resultNode = (resultNode == null) ? partNode : astFactory.createAdd(resultNode, partNode);
      }
      return resultNode;
    }
  }

  private Node createNodeForMsgPart(
      Part part, MsgOptions options, Map<String, Node> placeholderMap) {
    final Node partNode;
    if (part.isPlaceholder()) {
      partNode = checkNotNull(placeholderMap.get(part.getPlaceholderName())).cloneTree();
    } else {
      // The part is just a string literal.
      String s = part.getString();
      if (options.escapeLessThan) {
        // Note that "&" is not replaced because the translation can contain HTML entities.
        s = s.replace("<", "&lt;");
      }
      if (options.unescapeHtmlEntities) {
        // Unescape entities that need to be escaped when embedding HTML or XML in data/attributes
        // of an HTML/XML document. See https://www.w3.org/TR/xml/#sec-predefined-ent.
        // Note that "&amp;" must be the last to avoid "creating" new entities.
        // To print an html entity in the resulting message, double-escape: `&amp;amp;`.
        s =
            s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
      }
      partNode = astFactory.createString(s);
    }
    return partNode;
  }

  private static MsgOptions getOptions(JsMessage message, @Nullable Node optionsNode)
      throws MalformedException {
    MsgOptions options = new MsgOptions();
    if (optionsNode == null) {
      return options;
    }
    if (!optionsNode.isObjectLit()) {
      throw new MalformedException("OBJLIT node expected", optionsNode);
    }
    for (Node aNode = optionsNode.getFirstChild(); aNode != null; aNode = aNode.getNext()) {
      if (!aNode.isStringKey()) {
        throw new MalformedException("STRING_KEY node expected as OBJLIT key", aNode);
      }
      String optName = aNode.getString();
      Node value = aNode.getFirstChild();
      switch (optName) {
        case "html":
          options.escapeLessThan = value.isTrue();
          if (!value.isTrue() && !value.isFalse()) {
            throw new MalformedException("html: Literal true or false expected", value);
          }
          break;
        case "unescapeHtmlEntities":
          options.unescapeHtmlEntities = value.isTrue();
          if (!value.isTrue() && !value.isFalse()) {
            throw new MalformedException(
                "unescapeHtmlEntities: Literal true or false expected", value);
          }
          break;
        case "example":
        case "original_code":
          {
            // Verify this format to inform users ASAP if they're supplying something unexpected.
            // These options are only used when generating the XMB file, but normal compilations
            // happen much more frequently than that, so we want to report these errors now.
            // ```
            // {
            //    example: {
            //      'name': 'George'
            //    },
            //    original_code: {
            //      'name': 'getName()'
            //    }
            // }
            // ```
            if (!value.isObjectLit()) {
              throw new MalformedException(optName + ": object literal required", value);
            }
            final ImmutableSet<String> placeholders = message.placeholders();
            Node stringKeyNode;
            for (stringKeyNode = value.getFirstChild();
                stringKeyNode != null;
                stringKeyNode = stringKeyNode.getNext()) {
              if (!stringKeyNode.isStringKey()) {
                throw new MalformedException("placeholder name required", stringKeyNode);
              }
              String placeholderName = stringKeyNode.getString();
              if (!placeholders.contains(placeholderName)) {
                throw new MalformedException("unknown placeholder name", stringKeyNode);
              }
              Node placeholderValue = stringKeyNode.getOnlyChild();
              if (!placeholderValue.isStringLit()) {
                throw new MalformedException("string literal required", placeholderValue);
              }
            }
            break;
          }

        default:
          throw new MalformedException("Unexpected option", aNode);
      }
    }
    return options;
  }

  /** Options for escaping characters in translated messages. */
  private static class MsgOptions {
    // Replace `'<'` with `'&lt;'` in the message.
    private boolean escapeLessThan = false;
    // Replace these escaped entities with their literal characters in the message
    // (Overrides escapeLessThan)
    // '&lt;' -> '<'
    // '&gt;' -> '>'
    // '&apos;' -> "'"
    // '&quot;' -> '"'
    // '&amp;' -> '&'
    private boolean unescapeHtmlEntities = false;
  }

  /** Merges consecutive string parts in the list of message parts. */
  private static List<Part> mergeStringParts(List<Part> parts) {
    List<Part> result = new ArrayList<>();
    for (Part part : parts) {
      if (part.isPlaceholder()) {
        result.add(part);
      } else {
        Part lastPart = result.isEmpty() ? null : Iterables.getLast(result);
        if (lastPart == null || lastPart.isPlaceholder()) {
          result.add(part);
        } else {
          result.set(result.size() - 1, StringPart.create(lastPart.getString() + part.getString()));
        }
      }
    }
    return result;
  }

  /**
   * Checks that a node is a valid string expression (either a string literal or a concatenation of
   * string literals).
   *
   * @throws IllegalArgumentException if the node is null or the wrong type
   */
  private static void checkStringExprNode(@Nullable Node node) {
    if (node == null) {
      throw new IllegalArgumentException("Expected a string; found: null");
    }
    switch (node.getToken()) {
      case STRINGLIT:
      case TEMPLATELIT:
        break;
      case ADD:
        Node c = node.getFirstChild();
        checkStringExprNode(c);
        checkStringExprNode(c.getNext());
        break;
      default:
        throw new IllegalArgumentException("Expected a string; found: " + node.getToken());
    }
  }

  /**
   * Holds information about the protected form of a translatable message that appears in the AST.
   *
   * <p>The original translatable messages are replaced with this protected form by the logic in
   * `ReplaceMessages` to protect the message information through the optimization passes.
   *
   * <pre><code>
   *   // original
   *   var MSG_GREETING = goog.getMsg('Hello, {$name}!', {name: person.getName()}, {html: false});
   *   // protected form
   *   var MSG_GREETING = __jscomp_define_msg__(
   *       {
   *         'key': 'MSG_GREETING',
   *         'msg_text': 'Hello, {$name}!',
   *         'escapeLessThan': ''
   *       },
   *       {'name': person.getName()});
   * </code></pre>
   */
  public static class ProtectedJsMessage {

    private final JsMessage jsMessage;
    // The expression Node that defines the message and should be replaced with the localized
    // message.
    private final Node definitionNode;
    // e.g. `{ name: x.getName(), age: x.getAgeString() }`
    @Nullable private final Node substitutionsNode;
    // Replace `'<'` with `'&lt;'` in the message.
    private final boolean escapeLessThan;
    // Replace these escaped entities with their literal characters in the message
    // (Overrides escapeLessThan)
    // '&lt;' -> '<'
    // '&gt;' -> '>'
    // '&apos;' -> "'"
    // '&quot;' -> '"'
    // '&amp;' -> '&'
    private final boolean unescapeHtmlEntities;

    private ProtectedJsMessage(
        JsMessage jsMessage,
        Node definitionNode,
        @Nullable Node substitutionsNode,
        boolean escapeLessThan,
        boolean unescapeHtmlEntities) {
      this.jsMessage = jsMessage;
      this.definitionNode = definitionNode;
      this.substitutionsNode = substitutionsNode;
      this.escapeLessThan = escapeLessThan;
      this.unescapeHtmlEntities = unescapeHtmlEntities;
    }

    @Nullable
    public static ProtectedJsMessage fromAstNode(Node node, JsMessage.IdGenerator idGenerator) {
      if (!node.isCall()) {
        return null;
      }
      final Node calleeNode = checkNotNull(node.getFirstChild(), node);
      if (!calleeNode.matchesName(ReplaceMessagesConstants.DEFINE_MSG_CALLEE)) {
        return null;
      }
      final Node propertiesNode = checkNotNull(calleeNode.getNext(), calleeNode);
      final Node substitutionsNode = propertiesNode.getNext();
      boolean escapeLessThanOption = false;
      boolean unescapeHtmlEntitiesOption = false;
      JsMessage.Builder jsMessageBuilder = new JsMessage.Builder();
      checkState(propertiesNode.isObjectLit(), propertiesNode);
      String msgKey = null;
      String meaning = null;
      for (Node strKey = propertiesNode.getFirstChild();
          strKey != null;
          strKey = strKey.getNext()) {
        checkState(strKey.isStringKey(), strKey);
        String key = strKey.getString();
        Node valueNode = strKey.getOnlyChild();
        checkState(valueNode.isStringLit(), valueNode);
        String value = valueNode.getString();
        switch (key) {
          case "key":
            jsMessageBuilder.setKey(value);
            msgKey = value;
            break;
          case "meaning":
            jsMessageBuilder.setMeaning(value);
            meaning = value;
            break;
          case "alt_id":
            jsMessageBuilder.setAlternateId(value);
            break;
          case "msg_text":
            try {
              JsMessageVisitor.parseMessageTextIntoPartsForBuilder(jsMessageBuilder, value);
            } catch (PlaceholderFormatException unused) {
              // Somehow we stored the protected message text incorrectly, which should never
              // happen.
              throw new IllegalStateException(
                  valueNode.getLocation() + ": Placeholder incorrectly formatted: >" + value + "<");
            }
            break;
          case "escapeLessThan":
            // Just being present enables this option
            escapeLessThanOption = true;
            break;
          case "unescapeHtmlEntities":
            // just being present enables this option
            unescapeHtmlEntitiesOption = true;
            break;
          default:
            throw new IllegalStateException("unknown protected message key: " + strKey);
        }
      }
      final String externalMessageId = JsMessageVisitor.getExternalMessageId(msgKey);
      if (externalMessageId != null) {
        // MSG_EXTERNAL_12345 = ...
        jsMessageBuilder.setIsExternalMsg(true).setId(externalMessageId);
      } else {
        // NOTE: If the message was anonymous one (assigned to a variable or property named
        // MSG_UNNAMED_XXX), the key we have here will be the one generated from the message
        // text, and we won't end up setting the isAnonymous flag. Nothing seems to use that
        // flag...
        // TODO(bradfordcsmith): Maybe remove the isAnonymous flag for jsMessage objects?
        final String meaningForIdGeneration =
            meaning != null ? meaning : JsMessageVisitor.removeScopedAliasesPrefix(msgKey);
        if (idGenerator != null) {
          jsMessageBuilder.setId(
              idGenerator.generateId(meaningForIdGeneration, jsMessageBuilder.getParts()));
        } else {
          jsMessageBuilder.setId(meaningForIdGeneration);
        }
      }
      return new ProtectedJsMessage(
          jsMessageBuilder.build(),
          node,
          substitutionsNode,
          escapeLessThanOption,
          unescapeHtmlEntitiesOption);
    }
  }
}
