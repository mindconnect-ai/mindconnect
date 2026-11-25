package ai.mindconnect.extension.runtime;

import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.extension.adapter.file.FileBrandActivationRepository;
import ai.mindconnect.extension.adapter.file.FileExtensionActivationRepository;
import ai.mindconnect.extension.adapter.file.FileInstalledSeedRepository;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The extensions' decisions on files, written by the runtime's mapper: a
 * namespace's under {@code <dataDir>/<namespace>/system}, the brands' under
 * {@code <dataDir>/system/extension-brands}.
 */
public final class FileExtensionPlugin extends ExtensionPlugin {

    private final Path dataDir;

    /** @param dataDir the installation's data directory ({@code mindconnect.data.base-dir}) */
    public FileExtensionPlugin(Path dataDir, boolean strict, Set<String> disabledByOperator) {
        super(strict, disabledByOperator);
        this.dataDir = dataDir;
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        ObjectMapper json = registry.get(ObjectMapper.class);
        ScopeSupplier scope = registry.get(ScopeSupplier.class);
        return new Stores() {
            @Override public ExtensionActivationRepository activations() {
                return new FileExtensionActivationRepository(dataDir, json, scope);
            }
            @Override public InstalledSeedRepository installedSeeds() {
                return new FileInstalledSeedRepository(dataDir, json, scope);
            }
            @Override public BrandActivationRepository brands() { return new FileBrandActivationRepository(dataDir, json); }
        };
    }
}
