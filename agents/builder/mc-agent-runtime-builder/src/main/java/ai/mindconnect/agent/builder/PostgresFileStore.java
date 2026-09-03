package ai.mindconnect.agent.builder;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.pg.PgFileStore;
import ai.mindconnect.jdbc.Sql;

/**
 * Uploads in Postgres for {@link AgentRuntimeBuilder#usePostgres}, when the
 * file-store modules are present. Kept apart from the builder so they stay
 * optional — nothing here is linked unless {@link #present()} said yes.
 */
final class PostgresFileStore {

    private PostgresFileStore() {
    }

    static boolean present() {
        try {
            Class.forName("ai.mindconnect.filestore.pg.PgFileStore");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static FileStore open(Sql sql) {
        return new PgFileStore(sql).initSchema();
    }
}
