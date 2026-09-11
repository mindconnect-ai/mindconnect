package ai.mindconnect.common;

/**
 * A save carried a version that is no longer the stored one: someone else saved
 * the entity in between. Nothing was written. The caller shows the current state
 * and lets the user decide — it does not retry with the newer version, which
 * would overwrite exactly the change the check just caught.
 */
public class StaleVersionException extends DomainException {

    public static final String CODE = "CONFLICT";

    private final long expectedVersion;
    private final long storedVersion;

    public StaleVersionException(String entity, String id, long expectedVersion, long storedVersion) {
        super(CODE, entity + " " + id + " was changed meanwhile (edited version " + expectedVersion
                + ", stored version " + storedVersion + ")");
        this.expectedVersion = expectedVersion;
        this.storedVersion = storedVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long storedVersion() {
        return storedVersion;
    }
}
