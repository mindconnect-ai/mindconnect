package ai.mindconnect.agent.voice.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a client sends as a JSON text frame. Audio itself travels in binary
 * frames between {@link Start} (or the previous turn) and {@link AudioEnd}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = VoiceCommand.Start.class, name = "start"),
        @JsonSubTypes.Type(value = VoiceCommand.AudioEnd.class, name = "audio.end"),
        @JsonSubTypes.Type(value = VoiceCommand.Text.class, name = "text"),
        @JsonSubTypes.Type(value = VoiceCommand.Cancel.class, name = "cancel"),
        @JsonSubTypes.Type(value = VoiceCommand.Bye.class, name = "bye")
})
public sealed interface VoiceCommand {

    /**
     * Opens the conversation, once per connection.
     *
     * @param agent       the agent's name (or id) — required unless {@code sessionId} resumes one
     * @param sessionId   a session of the caller's to continue, or {@code null} for a new one
     * @param language    ISO-639-1 code of the spoken language, or {@code null} for the server's default
     * @param inputFormat the container of the audio the client sends, e.g. {@code webm}, {@code wav}
     * @param voice       the voice to answer in, or {@code null} for the speech config's
     * @param message     an optional first message, run as a turn right away (e.g. the brief
     *                    that makes the agent open the conversation)
     */
    record Start(String agent, String sessionId, String language, String inputFormat, String voice,
                 String message) implements VoiceCommand {
    }

    /** The binary frames since the last turn are one utterance: transcribe it and answer. */
    record AudioEnd() implements VoiceCommand {
    }

    /** A typed message instead of speech. */
    record Text(String text) implements VoiceCommand {
    }

    /** Stop the running turn and its speech — the caller started talking. */
    record Cancel() implements VoiceCommand {
    }

    /** End the conversation; the server closes the connection. */
    record Bye() implements VoiceCommand {
    }
}
