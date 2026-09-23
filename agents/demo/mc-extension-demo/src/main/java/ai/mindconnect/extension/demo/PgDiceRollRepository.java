package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Rolls as rows of {@code ext_demo_dungeon.dice_roll} — the extension's own
 * schema, as {@code contributes.persistence.schema} declares, so the core's
 * tables stay the core's and uninstalling is one {@code DROP SCHEMA}. Keyed by
 * {@code (namespace, id)}; the namespace column is what a namespace's purge
 * would find the rows by, once the purge looks beyond the current schema.
 */
final class PgDiceRollRepository implements DiceRollRepository {

    static final String SCHEMA = "ext_demo_dungeon";

    private final DocumentTable<DiceRoll> rolls;
    private final Namespace namespace;

    PgDiceRollRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.rolls = DocumentTable.of(DiceRoll.class)
                .table(SCHEMA + ".dice_roll")
                .partitionKey("namespace", "TEXT", r -> namespace.value())
                .id("id", "TEXT", DiceRoll::id)
                .requiredColumn("rolled_at", "TEXT", r -> r.at().toString())
                .index("namespace", "rolled_at")
                .build(sql);
        sql.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        rolls.createSchema();
    }

    @Override
    public void append(DiceRoll roll) {
        rolls.insert(roll);
    }

    @Override
    public List<DiceRoll> since(Instant from) {
        // ISO-8601 instants sort as text, which is what the column holds.
        return rolls.find("WHERE namespace = ? AND rolled_at >= ? ORDER BY rolled_at", namespace.value(), from.toString());
    }
}
