package ai.mindconnect.agent.protocol;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.protocol.item.Role;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One execution over a conversation: reads the conversation's items as
 * context, appends the items it produces, and carries the run lifecycle.
 * The API unit of the protocol — created by
 * {@link ai.mindconnect.agent.protocol.api.AgentResponses#create}, observed
 * via {@link ai.mindconnect.agent.protocol.event.ResponseEvent} streams.
 *
 * <p>Sub-agent delegation makes the protocol recursive: a child response has
 * {@code parentResponseId} and {@code spawnedByItemId} set, and the parent's
 * {@code AgentCall} item carries the child's id — clients follow either
 * direction with the same API, one level deeper.
 *
 * @param sessionId        the agent-side binding (conversation + agent
 *                         definition + runtime state); the conversation id is
 *                         resolvable through it
 * @param parentResponseId {@code null} for root responses
 * @param spawnedByItemId  id of the parent's {@code AgentCall} item that
 *                         spawned this response; {@code null} for roots
 * @param output           the items this execution produced, in order
 * @param incompleteReason set exactly when {@code status == INCOMPLETE}
 * @param error            set exactly when {@code status == FAILED}
 * @param metadata         the EXTENSION SLOT for backend-specific data
 *                         (key convention: {@code openai.*}, {@code mc.*}).
 *                         The typed components are the backend-neutral
 *                         contract; anything a specific backend knows extra
 *                         goes here as data — never as a Response subtype,
 *                         which would break serialization totality, sealed
 *                         exhaustiveness and backend neutrality. Neutral
 *                         clients ignore it; knowing clients read known keys.
 */
public record Response(
        String id,
        String conversationId,
        String sessionId,
        String agentName,
        ResponseStatus status,
        IncompleteReason incompleteReason,
        String parentResponseId,
        String spawnedByItemId,
        List<ConversationItemRecord> output,
        Usage usage,
        ResponseError error,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant completedAt
) {

    /**
     * Convenience (mirrors OpenAI's {@code response.output_text}): the text
     * of the LAST assistant message in the output — the final answer for the
     * common chat case. Empty string when the response produced no assistant
     * text (yet), e.g. while {@code IN_PROGRESS} or when it ended on an
     * {@code ApprovalRequest}.
     */
    /**
     * Function calls without a matching {@code FunctionCallOutput} — the
     * "open" calls in the derived-openness sense. For a response that ended
     * {@code INCOMPLETE(WAITING_FOR_TOOL_OUTPUT)} these are the calls the
     * client must execute and answer.
     */
    public java.util.List<ConversationItem.FunctionCall> openFunctionCalls() {
        java.util.Set<String> closed = output.stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.FunctionCallOutput.class::isInstance)
                .map(item -> ((ConversationItem.FunctionCallOutput) item).callId())
                .collect(java.util.stream.Collectors.toSet());
        return output.stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.FunctionCall.class::isInstance)
                .map(ConversationItem.FunctionCall.class::cast)
                .filter(call -> !closed.contains(call.callId()))
                .toList();
    }

    public String outputText() {
        return output.stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.Message.class::isInstance)
                .map(ConversationItem.Message.class::cast)
                .filter(m -> m.role() == Role.ASSISTANT)
                .reduce((first, last) -> last)
                .stream()
                .flatMap(m -> m.content().stream())
                .filter(ContentPart.Text.class::isInstance)
                .map(part -> ((ContentPart.Text) part).text())
                .reduce("", String::concat);
    }
}
