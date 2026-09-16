package ai.mindconnect.filestore;

/**
 * Creates the file store for one persistence backend — a filesystem
 * directory, Postgres, … — so that whoever assembles a runtime picks a
 * factory once instead of switching over backends where the store is used.
 */
public interface FileStoreFactory {

    FileStore fileStore();
}
