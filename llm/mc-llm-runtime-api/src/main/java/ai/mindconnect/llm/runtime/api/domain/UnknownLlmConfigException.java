package ai.mindconnect.llm.runtime.api.domain;

/** Thrown when a caller names a config the gateway does not know. */
public class UnknownLlmConfigException extends RuntimeException {

    public UnknownLlmConfigException(String configName) {
        super("LlmConfig not found: " + configName);
    }
}
