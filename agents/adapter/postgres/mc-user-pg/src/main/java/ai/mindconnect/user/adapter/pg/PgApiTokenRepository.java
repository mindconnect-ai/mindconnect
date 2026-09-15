package ai.mindconnect.user.adapter.pg;

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
 * {@code mc_api_token}, keyed by the id alone — tokens are installation-wide like their users — with the owner and
 * the hash of the secret beside the document — the hash under a unique index,
 * because authenticating a request is a lookup by it.
 */
public class PgApiTokenRepository implements ApiTokenRepository {

    private static final String TABLE = "mc_api_token";

    private final DocumentTable<ApiToken> tokens;
    private final Sql sql;

    public PgApiTokenRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgApiTokenRepository(Sql sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.tokens = DocumentTable.of(ApiToken.class)
                .table(TABLE)
                .id("id", "TEXT", t -> t.id().value())
                .requiredColumn("user_id", "TEXT", t -> t.userId().value())
                .requiredColumn("token_hash", "TEXT", ApiToken::tokenHash)
                .uniqueIndex("token_hash")
                .index("user_id")
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
        return tokens.findById(id.value());
    }

    @Override
    public Optional<ApiToken> findByHash(String tokenHash) {
        return tokens.findOne("WHERE token_hash = ?", tokenHash);
    }

    @Override
    public List<ApiToken> findByUser(UserId userId) {
        return tokens.find("WHERE user_id = ? ORDER BY id", userId.value());
    }

    @Override
    public void deleteById(ApiTokenId id) {
        tokens.deleteById(id.value());
    }

    /** An {@code UPDATE}, never the upsert of {@link #save}: a row revoked in between stays gone. */
    @Override
    public void recordUse(ApiTokenId id, Instant at) {
        findById(id).ifPresent(token -> sql.update(
                "UPDATE " + TABLE + " SET doc = ?, updated_at = now() WHERE id = ?",
                sql.json().jsonb(token.usedAt(at)), id.value()));
    }
}
