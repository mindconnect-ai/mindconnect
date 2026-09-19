package ai.mindconnect.credentials.adapter.pg;

import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OAuthProviderRepository} on Postgres. One row of
 * {@code mc_oauth_provider} per app registration, the lookup name beside the
 * document under a unique index — a {@code ConnectionSpec} names it, so two
 * registrations of one name would make which one is used an accident.
 */
public class PgOAuthProviderRepository implements OAuthProviderRepository {

    private static final String TABLE = "mc_oauth_provider";

    private final DocumentTable<OAuthProvider> providers;

    public PgOAuthProviderRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgOAuthProviderRepository(Sql sql) {
        Objects.requireNonNull(sql, "sql");
        this.providers = DocumentTable.of(OAuthProvider.class)
                .table(TABLE)
                .id("id", "TEXT", p -> p.id().toString())
                .requiredColumn("name", "TEXT", OAuthProvider::name)
                .uniqueIndex("name")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgOAuthProviderRepository initSchema() {
        providers.createSchema();
        return this;
    }

    @Override
    public void save(OAuthProvider provider) {
        providers.save(provider);
    }

    @Override
    public Optional<OAuthProvider> findById(UUID id) {
        return providers.findById(id.toString());
    }

    @Override
    public Optional<OAuthProvider> findByName(String name) {
        return name == null ? Optional.empty() : providers.findOne("WHERE name = ?", name);
    }

    @Override
    public List<OAuthProvider> findAll() {
        return providers.find("ORDER BY name");
    }

    @Override
    public void deleteById(UUID id) {
        providers.deleteById(id.toString());
    }
}
