package ai.mindconnect.agent.registry.domain;

/** What became of one member of an import, or of a package's removal. */
public enum ImportStatus {

    /** Newly installed. */
    IMPORTED,

    /** Replaced an entity of the same name, under {@link ImportMode#OVERWRITE}. */
    UPDATED,

    /** Deleted from this installation, by removing a package. */
    REMOVED,

    /** Left as it is: already there and the mode said to keep it, left out, or not there to remove. */
    SKIPPED,

    /** Could not be installed — the detail says why. The rest of the import goes on. */
    FAILED;

    /** Whether this status means nothing is broken. */
    public boolean ok() {
        return this != FAILED;
    }
}
