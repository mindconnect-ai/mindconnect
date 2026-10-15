package ai.mindconnect.agent.protocol.item;

import java.util.List;
import java.util.Map;

/**
 * The atom of the agent protocol: one entry of a conversation's log and of a
 * response's output. Deliberately NOT called a message — a message is one
 * KIND of item, next to function calls, their outputs, reasoning blocks and
 * approval requests. Cramming those into messages is exactly what Chat
 * Completions had to do (tool calls inside assistant messages, results as
 * {@code role: "tool"}); generalizing to items is what replaced it. The same type serves as the turn format (transcript
 * entries), the storage format (conversation store), the stream format
 * (wrapped in {@code OutputItemAdded/Done} events) and the API format.
 *
 * <p>Items are immutable values: what happened, said once. Where one item
 * answers another it refers back by id — a {@link FunctionCallOutput}
 * "closes" its {@link FunctionCall}, an {@link ApprovalResponse} answers an
 * {@link ApprovalRequest}.
 *
 * <p>What MEMORY did to an item — compacted, taken out of the window — is
 * deliberately not an item: it is a property of the entry that holds it
 * ({@code MemoryEntry}, and the message row behind it). Expressing it as
 * annotation items meant a second vocabulary that every reader had to
 * interpret before it could see the conversation.
 *
 * <p><b>Derived openness:</b> a {@link FunctionCall} or {@link AgentCall}
 * without a matching {@link FunctionCallOutput} (same {@code callId}) is
 * <em>open</em>. On resume, an open call is executed again — unless a
 * {@link FunctionCallDispatched} marker shows the execution had already
 * started, in which case a non-idempotent tool must fail with
 * "interrupted before completion" instead of running twice.
 */
public sealed interface ConversationItem {

    // ── Conversation content ────────────────────────────────────────────────

    /** A user, assistant or system message, composed of typed content parts. */
    record Message(Role role, List<ContentPart> content) implements ConversationItem {

        public static Message user(String text) {
            return new Message(Role.USER, List.of(new ContentPart.Text(text)));
        }
        public static Message assistant(String text) {
            return new Message(Role.ASSISTANT, List.of(new ContentPart.Text(text)));
        }
        public static Message system(String text) {
            return new Message(Role.SYSTEM, List.of(new ContentPart.Text(text)));
        }
    }

    /**
     * A model reasoning block. {@code signature} is opaque provider replay
     * data (e.g. Anthropic thinking signatures); {@code null} when absent.
     */
    record Reasoning(String text, String signature) implements ConversationItem {}

    // ── Tool calling ────────────────────────────────────────────────────────

    /**
     * The model requested a tool invocation. Open until a
     * {@link FunctionCallOutput} with the same {@code callId} exists.
     */
    record FunctionCall(String callId, String name, Map<String, Object> arguments) implements ConversationItem {}

    /**
     * Marker: execution of {@code callId} has actually started. Written
     * immediately before dispatching a tool whose execution is not idempotent,
     * so that a resume after a crash can distinguish "never ran" (safe to run)
     * from "may have run" (must not run again).
     */
    record FunctionCallDispatched(String callId) implements ConversationItem {}

    /** Result of a tool invocation. {@code failed} lets the model react to errors. */
    record FunctionCallOutput(String callId, String output, boolean failed) implements ConversationItem {}

    // ── Sub-agents ──────────────────────────────────────────────────────────

    /**
     * A sub-agent delegation. Spawns a child response; {@code childResponseId}
     * is recorded at spawn time so a resume finds and resumes the existing
     * child instead of spawning a second one. Closed, like a function call,
     * by a {@link FunctionCallOutput} with the same {@code callId} carrying
     * the child's final answer.
     */
    record AgentCall(String callId, String agentName, String input, String childResponseId) implements ConversationItem {}

    // ── Interaction (approval, login, input) ────────────────────────────────

    /**
     * The run needs something from a human before it can continue: the turn
     * ends with status {@code INCOMPLETE(WAITING_FOR_APPROVAL)} and this item
     * as the last output. In nested delegation the request bubbles up: every
     * level appends it, {@code originResponseId} points at the response that
     * raised it.
     *
     * @param kind    discriminator for clients, e.g. "tool_approval",
     *                "oauth_login", "user_input"
     * @param payload kind-specific data (tool name and arguments, an
     *                authorization URL, a question to the user, …)
     */
    record ApprovalRequest(String requestId, String kind, Map<String, Object> payload,
                           String originResponseId) implements ConversationItem {

        public static final String TOOL_APPROVAL = "tool_approval";

        /**
         * The approval a single {@link FunctionCall} needs. {@code requestId}
         * is the turn's id — it says WHICH request is waiting, not which call,
         * so the call is named in the payload and an
         * {@link ApprovalResponse} is paired to the oldest request of that id
         * that has no answer yet.
         */
        public static ApprovalRequest toolApproval(String requestId, FunctionCall call) {
            return new ApprovalRequest(requestId, TOOL_APPROVAL,
                    Map.of("callId", call.callId(),
                           "name", call.name(),
                           "arguments", call.arguments() == null ? Map.of() : call.arguments()),
                    null);
        }

        /** The call this request is about, or {@code null} for other kinds. */
        public String callId() {
            Object callId = payload == null ? null : payload.get("callId");
            return callId == null ? null : String.valueOf(callId);
        }
    }

    /** The human's answer, sent as input of the next response on the conversation. */
    record ApprovalResponse(String requestId, boolean approved, String note) implements ConversationItem {}
}
