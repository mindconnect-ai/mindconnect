package ai.mindconnect.extension.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A bundled record ({@code initial-data/**}) that a namespace got once: put
 * there by the seeding, or found there already when the seeding first looked.
 * Kept so that a record an admin deleted stays deleted — the seeding installs
 * only what the namespace never had, not whatever it lacks right now.
 *
 * <p>{@code kind} is the record's type as the Migrations screen names it
 * ({@code llm-config}, {@code agent}, {@code skill}, {@code workflow}),
 * {@code name} its identity there; {@code source} is the extension whose
 * manifest declared it, or {@link #HOST} for what the installation ships
 * itself. Like every store, the repository holding these is bound to one
 * namespace; nothing here names it.
 */
public record InstalledSeed(String kind, String name, String source, Instant installedAt) {

    /** The source of a seed no extension manifest declares. */
    public static final String HOST = "host";

    public InstalledSeed {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        source = source == null || source.isBlank() ? HOST : source;
        installedAt = installedAt == null ? Instant.now() : installedAt;
    }

    public static InstalledSeed of(String kind, String name, String source) {
        return new InstalledSeed(kind, name, source, Instant.now());
    }

    /** {@code kind:name} — the same id the Migrations screen gives the record. */
    public String key() {
        return key(kind, name);
    }

    public static String key(String kind, String name) {
        return kind + ":" + name;
    }
}
