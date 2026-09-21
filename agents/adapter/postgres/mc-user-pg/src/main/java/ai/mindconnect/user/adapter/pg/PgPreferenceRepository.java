package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.Preferences;
import ai.mindconnect.user.port.out.PreferenceRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PreferenceRepository} on Postgres. One row of
 * {@code mc_user_preference} per user and scope, keyed by both — a scope
 * cannot contain the separator ({@link Preferences#SCOPE}) — with the owner
 * beside the document, indexed, for reading everything a user has.
 */
public class PgPreferenceRepository implements PreferenceRepository {

    private static final String TABLE = "mc_user_preference";

    private final DocumentTable<Preferences> preferences;

    public PgPreferenceRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgPreferenceRepository(Sql sql) {
        Objects.requireNonNull(sql, "sql");
        this.preferences = DocumentTable.of(Preferences.class)
                .table(TABLE)
                .id("id", "TEXT", p -> id(p.userId(), p.scope()))
                .requiredColumn("user_id", "TEXT", p -> p.userId().value())
                .requiredColumn("scope", "TEXT", Preferences::scope)
                .index("user_id")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgPreferenceRepository initSchema() {
        preferences.createSchema();
        return this;
    }

    @Override
    public Optional<Preferences> find(UserId userId, String scope) {
        return preferences.findById(id(userId, scope));
    }

    @Override
    public List<Preferences> findByUser(UserId userId) {
        return preferences.find("WHERE user_id = ? ORDER BY scope", userId.value());
    }

    @Override
    public void save(Preferences preferences) {
        this.preferences.save(preferences);
    }

    @Override
    public void delete(UserId userId, String scope) {
        preferences.deleteById(id(userId, scope));
    }

    /** The scope first and a slash after it: a scope has no slash, a user id may. */
    private static String id(UserId userId, String scope) {
        return scope + "/" + userId.value();
    }
}
