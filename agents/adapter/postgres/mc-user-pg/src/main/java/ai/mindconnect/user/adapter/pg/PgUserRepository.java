package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserRepository} on Postgres. Each user is one row of {@code mc_user},
 * keyed by {@code (namespace, id)}. The repository is bound to one namespace
 * and every statement matches it.
 */
public class PgUserRepository implements UserRepository {

    private final DocumentTable<User> users;
    private final Namespace namespace;

    public PgUserRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgUserRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.users = DocumentTable.of(User.class)
                .table("mc_user")
                .partitionKey("namespace", "TEXT", u -> namespace.value())
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
        return users.findById(namespace.value(), id.value());
    }

    @Override
    public List<User> findAll() {
        return users.find("WHERE namespace = ? ORDER BY id", namespace.value());
    }

    @Override
    public void save(User user) {
        users.save(user);
    }
}
