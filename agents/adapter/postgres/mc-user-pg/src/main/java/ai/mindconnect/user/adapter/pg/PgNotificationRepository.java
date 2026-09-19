package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.port.out.NotificationRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link NotificationRepository} on Postgres. One row of
 * {@code mc_notification} per notification, keyed by the id alone —
 * notifications are installation-wide like their users — with the owner beside
 * the document, indexed, because reading a user's list is the only query there
 * is.
 */
public class PgNotificationRepository implements NotificationRepository {

    private static final String TABLE = "mc_notification";

    private final DocumentTable<Notification> notifications;

    public PgNotificationRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgNotificationRepository(Sql sql) {
        Objects.requireNonNull(sql, "sql");
        this.notifications = DocumentTable.of(Notification.class)
                .table(TABLE)
                .id("id", "TEXT", n -> n.id().value())
                .requiredColumn("user_id", "TEXT", n -> n.userId().value())
                .index("user_id")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgNotificationRepository initSchema() {
        notifications.createSchema();
        return this;
    }

    @Override
    public void save(Notification notification) {
        notifications.save(notification);
    }

    @Override
    public Optional<Notification> findById(NotificationId id) {
        return notifications.findById(id.value());
    }

    @Override
    public List<Notification> findByUser(UserId userId) {
        return notifications.find("WHERE user_id = ? ORDER BY id", userId.value());
    }

    @Override
    public void deleteById(NotificationId id) {
        notifications.deleteById(id.value());
    }
}
