package ai.mindconnect.agent.migration;

import java.time.Instant;

/** A migration that ran in a namespace: which, of which version, when, and what it did. */
public record AppliedMigration(String id, String version, Instant appliedAt, String report) {
}
