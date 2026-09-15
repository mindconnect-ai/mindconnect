package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link UserRepository} on Postgres. Each user is one row of {@code mc_user},
 * keyed by the id alone: users are installation-wide, they are not inside a
 * namespace.
 */
public class PgUserRepository implements UserRepository {

    private final DocumentTable<User> users;

    public PgUserRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgUserRepository(Sql sql) {
        this.users = DocumentTable.of(User.class)
                .table("mc_user")
                .id("id", "TEXT", u -> u.id().value())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgUserRepository initSchema() {
        users.createSchema();
        return this;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return users.findById(id.value());
    }

    @Override
    public List<User> findAll() {
        return users.find("ORDER BY id");
    }

    @Override
    public void save(User user) {
        users.save(user);
    }
}
