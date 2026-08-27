package ai.mindconnect.agent.domain;

import java.util.Map;
import java.util.UUID;

public sealed interface StreamEvent
        permits StreamEvent.Token, StreamEvent.ToolCallStarted, StreamEvent.ToolCallResult,
                StreamEvent.ToolCallFailed,
                StreamEvent.AskingLlm, StreamEvent.Reviewing, StreamEvent.ReviewerDecision,
                StreamEvent.ResponseRevised, StreamEvent.Done,
                StreamEvent.SubAgentStarted, StreamEvent.SubAgentEvent,
                StreamEvent.SubAgentDone, StreamEvent.SubAgentError,
                StreamEvent.ApprovalRequested {

    /** Verdict a response reviewer reached after running. */
    enum ReviewerVerdict { PASSED, MODIFIED, BLOCKED }

    record Token(String text) implements StreamEvent {}

    record ToolCallStarted(String toolName, Map<String, Object> arguments) implements StreamEvent {}

    record ToolCallResult(String toolName, String result, long durationMs) implements StreamEvent {}

    /**
     * Fired when a tool invocation could not produce a regular result — e.g. the
     * tool was unknown, threw an exception, or the registry refused to resolve
     * it. The agent loop still feeds {@code error} back to the LLM as a
     * tool-result string (so the model can react), but UI clients receive this
     * event instead of {@link ToolCallResult} for the failed call.
     */
    record ToolCallFailed(String toolName, String error, long durationMs) implements StreamEvent {}

    record AskingLlm() implements StreamEvent {}

    /**
     * Fired before a response reviewer runs. The CLI may show a "reviewing…" status
     * line. Always followed by exactly one {@link ReviewerDecision} for that reviewer
     * — and additionally by a {@link ResponseRevised} when the verdict was MODIFIED
     * or BLOCKED.
     */
    record Reviewing(String reviewerName) implements StreamEvent {}

    /**
     * Fired after a reviewer finished, regardless of outcome. Lets the CLI replace
     * the "reviewing…" status line with a short permanent marker like
     * "✓ answer-relevance-checker (passed)".
     */
    record ReviewerDecision(String reviewerName, ReviewerVerdict verdict) implements StreamEvent {}

    /**
     * Fired only when a reviewer altered the agent's draft answer (verdict MODIFIED
     * or BLOCKED). Carries the final text the user should see. The CLI is expected
     * to discard whatever it has already streamed and render {@link #finalText}
     * instead. Always preceded by a {@link ReviewerDecision} for the same reviewer.
     */
    record ResponseRevised(String finalText, String reason, boolean blocked) implements StreamEvent {}

    record Done() implements StreamEvent {}

    /**
     * A tool call needs a human — pushed on the ROOT turn's channel so the
     * open chat stream shows the approval card immediately. The durable
     * truth is the APPROVAL_REQUEST message (persisted FIRST, in the root
     * conversation for bubbled sub-agent requests); this event is only the
     * live mirror of it. {@code originSessionId}/{@code toolTaskId} are set
     * for bubbled requests and route the answer back down.
     */
    record ApprovalRequested(String requestId, String callId, String toolName,
                             Map<String, Object> arguments,
                             UUID originSessionId, String toolTaskId) implements StreamEvent {}

    /**
     * Fired when a sub-agent run was just dispatched. Carries the run's task id
     * (correlation key for following SubAgentEvent / SubAgentDone / SubAgentError),
     * the target agent's name, the depth of the run in the call tree (0 = root),
     * the spawned sub-session's id, and the {@code input} — the task message the
     * sub-agent was given. The sub-session id is the <em>durable</em> identifier —
     * UI clients key their card/list ids on it so a page reload mid-run can rebuild
     * the same nodes from persisted session state and keep receiving the run's live
     * patches; {@code input} lets the live card show the call's Input immediately,
     * like a normal tool call.
     */
    record SubAgentStarted(UUID taskId, String agentName, int depth, UUID subSessionId, String input)
            implements StreamEvent {}

    /**
     * Wraps a stream event coming from a sub-agent so the parent stream can carry it
     * to its own subscribers. Wrapping happens once per call-tree level — at depth 2
     * the inner event is itself a SubAgentEvent.
     */
    record SubAgentEvent(UUID taskId, StreamEvent inner) implements StreamEvent {}

    /**
     * Fired after a sub-agent run finished successfully. {@code subSessionId} points
     * at the sub-agent's own conversation/session; UI clients can use it to load the
     * full sub-conversation lazily. {@code finalText} is the sub-agent's final
     * response text — the same string the parent agent receives as the tool result —
     * so a live UI can show the answer inside the sub-agent card without waiting for
     * a page reload. May be {@code null} when the run produced no text.
     */
    record SubAgentDone(UUID taskId, String agentName, UUID subSessionId, String finalText)
            implements StreamEvent {}

    /**
     * Fired when a sub-agent run failed. The parent agent still receives a tool-result
     * with the error message — this event is for live UI feedback only.
     */
    record SubAgentError(UUID taskId, String agentName, String error) implements StreamEvent {}
}
