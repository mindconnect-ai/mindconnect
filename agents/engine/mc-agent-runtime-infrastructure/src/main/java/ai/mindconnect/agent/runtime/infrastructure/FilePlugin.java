package ai.mindconnect.agent.runtime.infrastructure;

import java.nio.file.Path;
import java.util.Map;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.SpiFileStoreFactory;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.vectorstore.tools.VectorStores;

/**
 * The files of an installation: the store uploads go into, the vector
 * stores they are indexed in, and the reader that hands a model the bytes of
 * a file a message refers to.
 */
public final class FilePlugin implements Plugin {

    private static final String FILESYSTEM = "filesystem";

    private final String backend;
    private final Path dataDir;

    /** @param backend the kind of file store: {@code filesystem}, or a backend registered for the SPI */
    public FilePlugin(String backend, Path dataDir) {
        this.backend = backend;
        this.dataDir = dataDir;
    }

    @Override
    public String name() {
        return "files";
    }

    @Override
    public void install(PluginRegistry registry) {
        // The filesystem store is built here, not found by the SPI: it writes with the runtime's mapper factory.
        registry.provide(FileStore.class, () -> FILESYSTEM.equals(backend)
                ? new FilesystemFileStore(dataDir, registry.get(ScopeSupplier.class),
                        registry.get(JsonMapperFactory.class))
                : new SpiFileStoreFactory(backend,
                        Map.of("baseDir", dataDir.toString()), registry.get(ScopeSupplier.class)).fileStore());
        registry.provide(VectorStores.class, () -> VectorStores.fromEnvironment(registry.get(ToolEnvironment.class))
                .orElseThrow(() -> new IllegalStateException(
                        "The vector stores could not be opened from the tool settings, see VectorStores.fromEnvironment")));
        registry.provide(MediaReader.class, () -> new FileStoreMediaReader(registry.get(FileStore.class)));
    }
}
