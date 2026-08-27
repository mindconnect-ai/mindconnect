package ai.mindconnect.message.domain;

/**
 * What a message IS — the discriminator the runtime derives execution state
 * from (see concept 16). A {@code TOOL_CALL} without a {@code TOOL_RESULT} of
 * the same {@code metadata.callId} is an <em>open</em> call; the interaction
 * types pair up the same way via {@code metadata.requestId}.
 *
 * <p>The metadata keys each type must carry are documented on the type — they
 * are structural, not decorative: the {@code ToolCalls} fold reads them
 * instead of parsing content JSON.
 *
 * <p>The former agent-to-agent values (TASK_ASSIGNMENT, TASK_RESULT,
 * BROADCAST, HANDOFF) were never written anywhere and are gone — delegation
 * is a child task on the queue now, not a message.
 */
public enum MessageType {

    /** Plain conversation text — user input or the agent's (final) answer. */
    CHAT,

    /** Assistant turn requesting tool calls. Metadata: {@code callIds}. Content: the calls + thinking blocks. */
    TOOL_CALL,

    /**
     * Marker: execution of {@code metadata.callId} has started — written
     * BEFORE the tool task is submitted, so a resume can tell "never ran"
     * from "may have run" and a non-idempotent tool is not run twice.
     */
    TOOL_DISPATCHED,

    /** Result of one tool call. Metadata: {@code callId}, {@code toolName}, {@code failed}. */
    TOOL_RESULT,

    /**
     * A human must decide before {@code metadata.callId} may run; the turn
     * ends {@code INCOMPLETE(WAITING_FOR_APPROVAL)}. Metadata:
     * {@code requestId}, {@code callId}, {@code kind}.
     */
    APPROVAL_REQUEST,

    /**
     * The human's answer — input of the NEXT turn on the conversation, paired
     * to the oldest unanswered request of the same {@code metadata.requestId}.
     * Metadata: {@code requestId}, {@code approved}.
     */
    APPROVAL_RESPONSE,

    /** Non-conversational note (seeding, migration markers). Never sent to the LLM. */
    SYSTEM
}
