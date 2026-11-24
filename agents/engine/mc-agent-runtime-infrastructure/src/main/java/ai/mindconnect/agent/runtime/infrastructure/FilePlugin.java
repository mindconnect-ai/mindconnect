package ai.mindconnect.agent.runtime.infrastructure;

import java.nio.file.Path;
import java.util.Map;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.SpiFileStoreFactory;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.vectorstore.tools.VectorStores;

/**
 * The files of an installation: the store uploads go into, the vector
 * stores they are indexed in, and the reader that hands a model the bytes of
 * a file a message refers to.
 */
public final class FilePlugin implements Plugin {

    private final String backend;
    private final Path dataDir;

    /** @param backend the kind of file store, as its factory is registered: {@code filesystem} */
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
        registry.provide(FileStore.class, () -> new SpiFileStoreFactory(backend,
                Map.of("baseDir", dataDir.toString()), registry.get(ScopeSupplier.class)).fileStore());
        registry.provide(VectorStores.class, () -> VectorStores.fromEnvironment(registry.get(ToolEnvironment.class))
                .orElseThrow(() -> new IllegalStateException(
                        "The vector stores could not be opened from the tool settings, see VectorStores.fromEnvironment")));
        registry.provide(MediaReader.class, () -> new FileStoreMediaReader(registry.get(FileStore.class)));
    }
}
