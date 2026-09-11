package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.port.out.ApiTokenRepository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ApiTokenRepository} on Postgres. Each token is one row of
 * {@code mc_api_token}, keyed by {@code (namespace, id)}, with the owner and
 * the hash of the secret beside the document — the hash under a unique index,
 * because authenticating a request is a lookup by it.
 */
public class PgApiTokenRepository implements ApiTokenRepository {

    private static final String TABLE = "mc_api_token";

    private final DocumentTable<ApiToken> tokens;
    private final Sql sql;
    private final Namespace namespace;

    public PgApiTokenRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgApiTokenRepository(Sql sql, Namespace namespace) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.tokens = DocumentTable.of(ApiToken.class)
                .table(TABLE)
                .partitionKey("namespace", "TEXT", t -> namespace.value())
                .id("id", "TEXT", t -> t.id().value())
                .requiredColumn("user_id", "TEXT", t -> t.userId().value())
                .requiredColumn("token_hash", "TEXT", ApiToken::tokenHash)
                .uniqueIndex("namespace", "token_hash")
                .index("namespace", "user_id")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgApiTokenRepository initSchema() {
        tokens.createSchema();
        return this;
    }

    @Override
    public void save(ApiToken token) {
        tokens.save(token);
    }

    @Override
    public Optional<ApiToken> findById(ApiTokenId id) {
        return tokens.findById(namespace.value(), id.value());
    }

    @Override
    public Optional<ApiToken> findByHash(String tokenHash) {
        return tokens.findOne("WHERE namespace = ? AND token_hash = ?", namespace.value(), tokenHash);
    }

    @Override
    public List<ApiToken> findByUser(UserId userId) {
        return tokens.find("WHERE namespace = ? AND user_id = ? ORDER BY id", namespace.value(), userId.value());
    }

    @Override
    public void deleteById(ApiTokenId id) {
        tokens.deleteById(namespace.value(), id.value());
    }

    /** An {@code UPDATE}, never the upsert of {@link #save}: a row revoked in between stays gone. */
    @Override
    public void recordUse(ApiTokenId id, Instant at) {
        findById(id).ifPresent(token -> sql.update(
                "UPDATE " + TABLE + " SET doc = ?, updated_at = now() WHERE namespace = ? AND id = ?",
                sql.json().jsonb(token.usedAt(at)), namespace.value(), id.value()));
    }
}
