package ai.mindconnect.agent.loop.domain;

import java.util.Map;

/**
 * What the loop reports of a turn: what passes between the model and the
 * tools. The names are those a client reads on its stream.
 *
 * <p>Two kinds travel on the same channel. What the loop reports is durable:
 * a {@link MessageWritten} is announced after the message is stored, so a
 * watcher never sees something a reload would not show, and the tool calls
 * and results it holds are announced just before it. What the model call
 * reports is not: {@link TextDelta} and {@link Thinking} are fragments of an
 * answer still being written, and the message that finally holds them
 * follows as a {@link MessageWritten}.
 *
 * <p>How a turn left is what the loop returns, not an event: a caller that
 * keeps turns knows more about their end than the loop does.
 */
public interface TurnStreamEvent extends StreamEvent {

    /** The model is asked: told just before every call of the model, and for no other reason. */
    record AskingLlm() implements TurnStreamEvent {
        @Override public String kind() {
            return "asking_llm";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of();
        }
    }

    /** A fragment of the answer text the model is writing. */
    record TextDelta(String text) implements TurnStreamEvent {
        @Override public String kind() {
            return "text_delta";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of("text", text);
        }
    }

    /** A fragment of the model's readable reasoning; for showing, never fed back to the model. */
    record Thinking(String text) implements TurnStreamEvent {
        @Override public String kind() {
            return "thinking";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of("text", text);
        }
    }

    /**
     * A tool call the model asked for.
     *
     * @param index where the call stands in the model's request, from 0: a
     *              request may ask for several calls at once
     */
    record ToolCallStarted(String callId, String toolName, Map<String, Object> arguments, int index)
            implements TurnStreamEvent {
        @Override public String kind() {
            return "tool_call_started";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("callId", callId, "toolName", toolName, "arguments", arguments,
                    "index", index);
        }
    }

    record ToolCallResult(String callId, String toolName, String result) implements TurnStreamEvent {
        @Override public String kind() {
            return "tool_call_result";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("callId", callId, "toolName", toolName, "result", result);
        }
    }

    /** A tool call went wrong; the model was told {@code error} and decides. */
    record ToolCallFailed(String callId, String toolName, String error) implements TurnStreamEvent {
        @Override public String kind() {
            return "tool_call_failed";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("callId", callId, "toolName", toolName, "error", error);
        }
    }

    /** A message is in the conversation now — the stored one, with its id. */
    record MessageWritten(TurnMessage message) implements TurnStreamEvent {
        @Override public String kind() {
            return "message";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of("message", new TurnMessageConverter().toJson(message));
        }
    }

    /** What the turn has cost, as the providers reported it; told when the turn leaves for good. */
    record TurnUsage(long inputTokens, long outputTokens) implements TurnStreamEvent {
        @Override public String kind() {
            return "turn_usage";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of("inputTokens", inputTokens, "outputTokens", outputTokens);
        }
    }
}
