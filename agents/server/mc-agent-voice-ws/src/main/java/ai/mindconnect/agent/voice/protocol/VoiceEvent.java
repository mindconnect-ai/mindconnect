package ai.mindconnect.agent.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the server sends as a JSON text frame. A {@link Speech} event is
 * followed by exactly one binary frame with its audio.
 */
public sealed interface VoiceEvent {

    @JsonProperty("type")
    String type();

    /** The conversation is open; {@code speech} says whether answers come as audio too. */
    record Ready(String sessionId, boolean speech) implements VoiceEvent {
        public String type() { return "ready"; }
    }

    /** What was heard in the last utterance. */
    record Transcript(String text) implements VoiceEvent {
        public String type() { return "transcript"; }
    }

    /** A fragment of the agent's answer as it forms. */
    record Token(String text) implements VoiceEvent {
        public String type() { return "agent.token"; }
    }

    /** The agent's whole answer — the authority over the tokens. */
    record Reply(String text) implements VoiceEvent {
        public String type() { return "agent.done"; }
    }

    /** One spoken sentence; its audio follows in the next binary frame. */
    record Speech(int seq, String text, String contentType) implements VoiceEvent {
        public String type() { return "speech"; }
    }

    /** The turn is over; the client may listen again. */
    record TurnDone() implements VoiceEvent {
        public String type() { return "turn.done"; }
    }

    /** Something went wrong; {@code code} is stable, {@code message} is for people. */
    record Error(String code, String message) implements VoiceEvent {
        public String type() { return "error"; }
    }
}
