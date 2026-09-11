package ai.mindconnect.common;

/**
 * Optimistic locking for entities edited as a whole — in a form, through a REST
 * {@code PUT}. The entity carries the version it was read with; a store saves it
 * only if that is still the stored version, and stores it one higher.
 *
 * <p>A {@code null} version means "no check": the save overwrites whatever is
 * stored, as every save did before versions existed. Seeds, imports and API
 * clients that never read a version keep working that way.
 */
public final class Versions {

    private Versions() {
    }

    /**
     * The version to store.
     *
     * @param stored   the stored entity's version; {@code null} when there is none, or
     *                 when it was written before versions existed — both count as 0
     * @param expected the version the entity being saved was read with; {@code null}: no check
     * @throws StaleVersionException when {@code expected} is not the stored version
     */
    public static long next(Long stored, Long expected, String entity, String id) {
        long current = stored == null ? 0 : stored;
        if (expected != null && expected != current) {
            throw new StaleVersionException(entity, id, expected, current);
        }
        return current + 1;
    }
}
