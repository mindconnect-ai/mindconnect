package ai.mindconnect.agent.protocol.api;

import ai.mindconnect.agent.protocol.item.ConversationItem;

import java.util.List;

/**
 * Command to create a response on an existing session. The input items are
 * appended to the session's conversation before the run starts — typically a
 * single user {@code Message}; after an interruption an
 * {@code ApprovalResponse} or the {@code FunctionCallOutput} of a client tool.
 *
 * <p>Everything else — system prompt, model, server-side tools, memory —
 * comes from the agent the session is bound to. The request only ADDS:
 * {@code clientTools} declares functions the client executes itself.
 *
 * @param background {@code false}: {@code create} returns the terminal
 *                   response. {@code true}: returns immediately with status
 *                   {@code QUEUED}/{@code IN_PROGRESS}; observe via
 *                   {@code get} or {@code subscribe}.
 */
public record ResponseRequest(
        String sessionId,
        List<ConversationItem> input,
        boolean background,
        List<ToolDefinition> clientTools
) {

    /** The common case: one user message, blocking, no client tools. */
    public static ResponseRequest text(String sessionId, String text) {
        return new ResponseRequest(sessionId, List.of(ConversationItem.Message.user(text)), false, List.of());
    }

    /** Answer an {@code ApprovalRequest} from an INCOMPLETE response. */
    public static ResponseRequest approval(String sessionId, String requestId, boolean approved) {
        return new ResponseRequest(sessionId,
                List.of(new ConversationItem.ApprovalResponse(requestId, approved, null)), false, List.of());
    }

    /** Deliver a client tool's result for an open {@code FunctionCall}. */
    public static ResponseRequest toolOutput(String sessionId, String callId, String output) {
        return new ResponseRequest(sessionId,
                List.of(new ConversationItem.FunctionCallOutput(callId, output, false)), false, List.of());
    }

    public ResponseRequest inBackground() {
        return new ResponseRequest(sessionId, input, true, clientTools);
    }

    public ResponseRequest withClientTools(List<ToolDefinition> tools) {
        return new ResponseRequest(sessionId, input, background, tools);
    }
}
