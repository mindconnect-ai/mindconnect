package ai.mindconnect.agent.runtime.api.domain;

/** Where a turn stands. */
public enum TurnStatus {

    /** Accepted, not running yet. */
    QUEUED,

    RUNNING,

    /** Stopped to wait for its tools or sub-agents; it goes on by itself when they are done. */
    WAITING,

    /**
     * Stopped because it has to ask: whether a tool may run, something of
     * the user, a human colleague. The turn has come back — nothing more
     * happens until the caller resumes it with answers to its interruptions.
     */
    INTERRUPTED,

    COMPLETED,

    /** Ended without an answer — the round cap or the output limit. */
    INCOMPLETE,

    CANCELLED,

    FAILED;

    /** Whether nothing more will happen in this turn, whatever anyone does. */
    public boolean isFinal() {
        return this == COMPLETED || this == INCOMPLETE || this == CANCELLED || this == FAILED;
    }
}
