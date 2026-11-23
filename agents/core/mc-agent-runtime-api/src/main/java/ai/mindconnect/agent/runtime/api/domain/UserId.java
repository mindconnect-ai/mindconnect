package ai.mindconnect.agent.runtime.api.domain;

/** Identifies the user a session belongs to. */
public record UserId(String value) {

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
