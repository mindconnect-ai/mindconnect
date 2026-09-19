package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.port.out.UserToolRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserToolRepository} on Postgres. One row of {@code mc_user_tool} per
 * binding, keyed by the id alone — installation-wide like its user — with the
 * owner beside the document and indexed, because reading one user's bindings
 * is what every turn does.
 */
public class PgUserToolRepository implements UserToolRepository {

    private static final String TABLE = "mc_user_tool";

    private final DocumentTable<UserTool> tools;

    public PgUserToolRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgUserToolRepository(Sql sql) {
        Objects.requireNonNull(sql, "sql");
        this.tools = DocumentTable.of(UserTool.class)
                .table(TABLE)
                .id("id", "TEXT", t -> t.id().value())
                .requiredColumn("user_id", "TEXT", t -> t.userId().value())
                .index("user_id")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgUserToolRepository initSchema() {
        tools.createSchema();
        return this;
    }

    @Override
    public void save(UserTool tool) {
        tools.save(tool);
    }

    @Override
    public Optional<UserTool> findById(UserToolId id) {
        return tools.findById(id.value());
    }

    @Override
    public List<UserTool> findByUser(UserId userId) {
        return tools.find("WHERE user_id = ? ORDER BY id", userId.value());
    }

    @Override
    public void deleteById(UserToolId id) {
        tools.deleteById(id.value());
    }
}
