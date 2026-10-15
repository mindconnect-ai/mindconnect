package ai.mindconnect.agent.protocol.openai;

/** Any failure talking to or mapping from the OpenAI API. */
public class OpenAiBackendException extends RuntimeException {

    public OpenAiBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
