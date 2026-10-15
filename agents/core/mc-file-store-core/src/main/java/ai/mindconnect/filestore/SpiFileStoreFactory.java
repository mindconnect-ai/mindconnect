package ai.mindconnect.filestore;

import ai.mindconnect.agent.ScopeSupplier;
import java.util.Map;

/**
 * A file store from a {@link FileStoreBackend} found on the classpath, by
 * type name — {@code filesystem} from {@code mc-file-store}, or whatever a
 * further module registers — opened with the given settings, working in the
 * namespace the scope names per call.
 */
public class SpiFileStoreFactory implements FileStoreFactory {

    private final String type;
    private final Map<String, String> config;
    private final ScopeSupplier scope;

    public SpiFileStoreFactory(String type, Map<String, String> config, ScopeSupplier scope) {
        this.type = type;
        this.config = Map.copyOf(config);
        this.scope = scope;
    }

    @Override
    public FileStore fileStore() {
        return FileStoreBackend.byType(type)
                .orElseThrow(() -> new IllegalStateException("No file store backend of type '" + type
                        + "' on the classpath — for 'filesystem' add mc-file-store"))
                .open(config, scope);
    }
}
