package ai.mindconnect.agent.protocol.runtime;

/** Failure or unsupported operation in the runtime-backed protocol adapter. */
public class RuntimeBackendException extends RuntimeException {

    public RuntimeBackendException(String message) {
        super(message);
    }

    public RuntimeBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
