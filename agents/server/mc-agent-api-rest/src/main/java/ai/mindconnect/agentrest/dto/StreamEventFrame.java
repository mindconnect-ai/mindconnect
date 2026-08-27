package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.domain.StreamEvent;

import java.util.Map;

public record StreamEventFrame(String type, String text, String toolName,
                                Map<String, Object> arguments, String result,
                                Long durationMs,
                                String reviewerName, String verdict,
                                String finalText, String reason, Boolean blocked,
                                String taskId, String agentName, Integer depth,
                                String error, String subSessionId,
                                StreamEventFrame inner) {

    /** Copy with {@code text} set — lets a mapping arm reuse the generic field. */
    private StreamEventFrame withText(String text) {
        return new StreamEventFrame(type, text, toolName, arguments, result, durationMs,
                reviewerName, verdict, finalText, reason, blocked, taskId, agentName, depth,
                error, subSessionId, inner);
    }

    public static StreamEventFrame from(StreamEvent event) {
        return switch (event) {
            case StreamEvent.Token t ->
                    new StreamEventFrame("token", t.text(), null, null, null, null,
                            null, null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.ToolCallStarted s ->
                    new StreamEventFrame("tool_call_started", null, s.toolName(), s.arguments(), null, null,
                            null, null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.ToolCallResult r ->
                    new StreamEventFrame("tool_call_result", null, r.toolName(), null, r.result(), r.durationMs(),
                            null, null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.ToolCallFailed f ->
                    new StreamEventFrame("tool_call_failed", null, f.toolName(), null, null, f.durationMs(),
                            null, null, null, null, null,
                            null, null, null, f.error(), null, null);
            case StreamEvent.AskingLlm a ->
                    new StreamEventFrame("asking_llm", null, null, null, null, null,
                            null, null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.Reviewing rv ->
                    new StreamEventFrame("reviewing", null, null, null, null, null,
                            rv.reviewerName(), null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.ReviewerDecision rd ->
                    new StreamEventFrame("reviewer_decision", null, null, null, null, null,
                            rd.reviewerName(), rd.verdict().name(), null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.ResponseRevised rr ->
                    new StreamEventFrame("response_revised", null, null, null, null, null,
                            null, null, rr.finalText(), rr.reason(), rr.blocked(),
                            null, null, null, null, null, null);
            case StreamEvent.Done d ->
                    new StreamEventFrame("done", null, null, null, null, null,
                            null, null, null, null, null,
                            null, null, null, null, null, null);
            case StreamEvent.SubAgentStarted s ->
                    new StreamEventFrame("sub_agent_started", s.input(), null, null, null, null,
                            null, null, null, null, null,
                            s.taskId().toString(), s.agentName(), s.depth(), null,
                            s.subSessionId() != null ? s.subSessionId().toString() : null, null);
            case StreamEvent.SubAgentEvent se ->
                    new StreamEventFrame("sub_agent_event", null, null, null, null, null,
                            null, null, null, null, null,
                            se.taskId().toString(), null, null, null, null, from(se.inner()));
            case StreamEvent.SubAgentDone sd ->
                    new StreamEventFrame("sub_agent_done", null, null, null, null, null,
                            null, null, sd.finalText(), null, null,
                            sd.taskId().toString(), sd.agentName(), null, null,
                            sd.subSessionId().toString(), null);
            case StreamEvent.ApprovalRequested ar ->
                    new StreamEventFrame("approval_requested", null, ar.toolName(),
                            ar.arguments(), null, null,
                            null, null, null, null, null,
                            // requestId/callId ride the generic id fields so the
                            // frame record needs no new components: taskId=requestId,
                            // subSessionId=originSessionId, error=toolTaskId, text=callId.
                            ar.requestId(), null, null,
                            ar.toolTaskId(),
                            ar.originSessionId() == null ? null : ar.originSessionId().toString(),
                            null)
                            .withText(ar.callId());
            case StreamEvent.SubAgentError sErr ->
                    new StreamEventFrame("sub_agent_error", null, null, null, null, null,
                            null, null, null, null, null,
                            sErr.taskId().toString(), sErr.agentName(), null, sErr.error(), null, null);
        };
    }
}
