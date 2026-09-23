package ai.mindconnect.extension.demo;

import java.time.Instant;
import java.util.List;

/**
 * The extension's own store: every die the tool rolled. Bound to one
 * namespace like every store of the host; the feature routes it by the
 * current scope. What {@code contributes.persistence} declares — files
 * under the extension's own directory, rows in its own schema, never the
 * core's tables.
 */
public interface DiceRollRepository {

    void append(DiceRoll roll);

    /** Every roll at or after {@code from}, oldest first. */
    List<DiceRoll> since(Instant from);
}
