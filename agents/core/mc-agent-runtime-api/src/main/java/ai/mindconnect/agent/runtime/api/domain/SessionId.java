package ai.mindconnect.agent.runtime.api.domain;

/** Identifies a session. */
public record SessionId(String value) {

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
