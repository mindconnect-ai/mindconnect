package ai.mindconnect.extension.port.out;

import ai.mindconnect.extension.domain.InstalledSeed;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which bundled records a namespace got once — what the seeding looks at
 * before it installs anything, so that it installs only what the namespace
 * never had. Only ever added to: a record deleted from the namespace stays
 * here, and that is the point.
 *
 * <p>Like every store, an implementation is bound to the one namespace it
 * serves; above it a routing proxy picks the adapter for the namespace the
 * work runs in.
 */
public interface InstalledSeedRepository {

    /** Everything recorded, in no particular order. */
    List<InstalledSeed> all();

    /** The {@link InstalledSeed#key() keys} of everything recorded. */
    default Set<String> keys() {
        return all().stream().map(InstalledSeed::key).collect(Collectors.toSet());
    }

    /** Adds {@code seeds}; a key recorded before keeps its first entry. */
    void record(Collection<InstalledSeed> seeds);
}
