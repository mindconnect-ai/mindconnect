package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link BrandActivationRepository} on Postgres: one row of
 * {@code mc_extension_brand_activation} per decision, keyed by
 * {@code (brand, extension_id)}. Installation-wide — no {@code namespace}
 * column, so deleting a namespace of the brand leaves the brand's decisions
 * alone.
 */
public final class PgBrandActivationRepository implements BrandActivationRepository {

    private final DocumentTable<BrandActivation> activations;

    public PgBrandActivationRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgBrandActivationRepository(Sql sql) {
        this.activations = DocumentTable.of(BrandActivation.class)
                .table("mc_extension_brand_activation")
                .partitionKey("brand", "TEXT", BrandActivation::brand)
                .id("extension_id", "TEXT", a -> a.extensionId().value())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgBrandActivationRepository initSchema() {
        activations.createSchema();
        return this;
    }

    @Override
    public Optional<BrandActivation> find(String brand, ExtensionId id) {
        return activations.findById(brand, id.value());
    }

    @Override
    public List<BrandActivation> all(String brand) {
        return activations.find("WHERE brand = ? ORDER BY extension_id", brand);
    }

    @Override
    public void save(BrandActivation activation) {
        activations.save(activation);
    }

    @Override
    public void delete(String brand, ExtensionId id) {
        activations.deleteById(brand, id.value());
    }
}
