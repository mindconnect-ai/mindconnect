package ai.mindconnect.agent.tools.virtualenv;

import java.io.IOException;

/** The server refused a call, or could not be reached ({@code status} 0). */
public class VirtualEnvClientException extends IOException {

    private final int status;

    public VirtualEnvClientException(int status, String message) {
        super(message);
        this.status = status;
    }

    public VirtualEnvClientException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }
}
