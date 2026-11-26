package ai.mindconnect.extension.runtime.pg;

import java.util.Set;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.extension.adapter.pg.PgBrandActivationRepository;
import ai.mindconnect.extension.adapter.pg.PgExtensionActivationRepository;
import ai.mindconnect.extension.adapter.pg.PgInstalledSeedRepository;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.extension.runtime.ExtensionPlugin;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The extensions' decisions in Postgres, on the runtime's {@link Sql}: a row per
 * decision in {@code mc_extension_activation}, per bundled record a namespace got
 * in {@code mc_installed_seed}, per brand decision in {@code mc_extension_brand_activation}.
 */
public final class PgExtensionPlugin extends ExtensionPlugin {

    public PgExtensionPlugin(boolean strict, Set<String> disabledByOperator) {
        super(strict, disabledByOperator);
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        Sql sql = registry.get(Sql.class);
        ScopeSupplier scope = registry.get(ScopeSupplier.class);
        return new Stores() {
            @Override public ExtensionActivationRepository activations() {
                return new PgExtensionActivationRepository(sql, scope).initSchema();
            }
            @Override public InstalledSeedRepository installedSeeds() {
                return new PgInstalledSeedRepository(sql, scope).initSchema();
            }
            @Override public BrandActivationRepository brands() { return new PgBrandActivationRepository(sql).initSchema(); }
        };
    }
}
