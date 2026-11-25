package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;

import ai.mindconnect.agent.loop.domain.StreamEvent;

/**
 * What the runtime tells of a turn as a whole — what the loop that runs it
 * cannot know: that the turn stands still, how it ended, that it is the turn
 * of an agent another turn called. Together with the loop's
 * {@link ai.mindconnect.agent.loop.domain.TurnStreamEvent} this is what a
 * client of a session draws; the names are those a client of the REST API
 * reads.
 *
 * <p>A kind the runtime does not know — a plugin's — is an {@link Other}.
 */
public interface SessionStreamEvent extends StreamEvent {

    /**
     * The turn has asked everything it has to ask and stands still: told
     * once after the requests of one stop, however many they are.
     */
    record Waiting() implements SessionStreamEvent {
        @Override public String kind() {
            return "waiting";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of();
        }
    }

    /**
     * The turn goes on: something it stood still for has been answered. What
     * the answer set in motion — a tool that runs now — may take its time
     * before the model is asked again.
     */
    record Resumed() implements SessionStreamEvent {
        @Override public String kind() {
            return "resumed";
        }

        @Override public Map<String, Object> toDetails() {
            return Map.of();
        }
    }

    /**
     * The turn came to its end.
     *
     * @param text             the answer; empty for a turn that ended without one
     * @param incompleteReason why a turn that ended {@link TurnStatus#INCOMPLETE} did, as the loop
     *                         names it ({@code MAX_ROUNDS}, {@code MAX_OUTPUT_TOKENS}); {@code null} otherwise
     */
    record Done(TurnStatus status, String text, String incompleteReason) implements SessionStreamEvent {
        @Override public String kind() {
            return "done";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("status", status.name(), "text", text,
                    "incompleteReason", incompleteReason);
        }
    }

    /** The turn broke off: it failed, or was cancelled before it could end itself. */
    record Error(TurnStatus status, String message) implements SessionStreamEvent {
        @Override public String kind() {
            return "error";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("status", status.name(), "message", message);
        }
    }

    /** A turn another turn started has begun: an agent that was called. */
    record SubAgentStarted(String agentName) implements SessionStreamEvent {
        @Override public String kind() {
            return "sub_agent_started";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("agentName", agentName);
        }
    }

    /** A turn another turn started is over. */
    record SubAgentDone(String agentName, String finalText) implements SessionStreamEvent {
        @Override public String kind() {
            return "sub_agent_done";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("agentName", agentName, "finalText", finalText);
        }
    }

    /** An event of a kind the runtime does not know. */
    record Other(SessionEvent event) implements SessionStreamEvent {
        @Override public String kind() {
            return event.kind();
        }

        @Override public Map<String, Object> toDetails() {
            return event.details();
        }
    }
}
