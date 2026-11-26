package ai.mindconnect.agent.runtime.api.domain;

/** Identifies a turn — one request of a user and everything the agent does to answer it. */
public record TurnId(String value) {

    public static TurnId of(String value) {
        return new TurnId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
