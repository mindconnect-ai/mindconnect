package ai.mindconnect.filestore.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreFactory;
import ai.mindconnect.jdbc.Sql;

/** The file store as Postgres tables, bound to one namespace, created with its schema. */
public class PgFileStoreFactory implements FileStoreFactory {

    private final Sql sql;
    private final Namespace namespace;

    public PgFileStoreFactory(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = namespace;
    }

    @Override
    public FileStore fileStore() {
        return new PgFileStore(sql, namespace).initSchema();
    }
}
