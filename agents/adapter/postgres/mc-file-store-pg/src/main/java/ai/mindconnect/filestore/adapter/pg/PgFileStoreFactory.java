package ai.mindconnect.filestore.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreFactory;
import ai.mindconnect.jdbc.Sql;

/** The file store as Postgres tables in the namespace the scope names per call, created with its schema. */
public class PgFileStoreFactory implements FileStoreFactory {

    private final Sql sql;
    private final ScopeSupplier scope;

    public PgFileStoreFactory(Sql sql, ScopeSupplier scope) {
        this.sql = sql;
        this.scope = scope;
    }

    @Override
    public FileStore fileStore() {
        return new PgFileStore(sql, scope).initSchema();
    }
}
