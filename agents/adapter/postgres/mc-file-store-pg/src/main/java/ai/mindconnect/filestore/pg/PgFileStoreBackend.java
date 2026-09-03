package ai.mindconnect.filestore.pg;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.Map;

/**
 * The {@code postgres} entry in the {@link FileStoreBackend} SPI, for hosts
 * that configure backends by name and strings — {@code url}, {@code user},
 * {@code password}. Opens an unpooled connection source of its own; a
 * Spring host that already has a pool builds {@link PgFileStore} directly.
 */
public final class PgFileStoreBackend implements FileStoreBackend {

    @Override
    public String type() {
        return "postgres";
    }

    @Override
    public FileStore open(Map<String, String> config) {
        String url = config.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The postgres file store needs a 'url' (jdbc:postgresql://…)");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        if (config.get("user") != null) ds.setUser(config.get("user"));
        if (config.get("password") != null) ds.setPassword(config.get("password"));
        return new PgFileStore(ds).initSchema();
    }
}
