package ai.mindconnect.taskqueue.jdbc;

import ai.mindconnect.taskqueue.LeaseLostException;
import ai.mindconnect.taskqueue.TaskFailure;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskQueueException;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Postgres-backed {@link TaskStore}: the cluster is the store, the queue does
 * not change. Atomicity comes from row locks ({@code FOR UPDATE SKIP LOCKED}
 * on the claim, {@code FOR UPDATE} on the multi-step transitions) — the state
 * TRANSITIONS themselves stay on {@link TaskRecord}, exactly as in the
 * in-memory store, so both stores can only ever disagree about locking, never
 * about semantics.
 *
 * <p>Every claim takes a lease ({@code lease_owner}, {@code lease_expires_at});
 * {@link #renewLease} keeps it, {@link #recoverExpired} reclaims what a dead
 * node left behind. {@link #sweepRunning} is therefore a no-op here: on a
 * shared store "RUNNING but not mine" usually means another LIVE node — only
 * the lease can tell a busy neighbour from a dead one.
 */
public final class JdbcTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskStore.class);

    private static final String COLUMNS =
            "id, type, status, payload, priority, parent_task_id, attempt, node_id, cancel_requested, "
            + "waiting_for, notifications, resumed, state, result, failure, run_after, "
            + "max_attempts, submitted_at, started_at, ended_at";

    private final DataSource dataSource;
    private final String nodeId;
    private final Duration lease;

    public JdbcTaskStore(DataSource dataSource, String nodeId, Duration lease) {
        this.dataSource = dataSource;
        this.nodeId = nodeId;
        this.lease = lease;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public JdbcTaskStore initSchema() {
        runSchema("schema-task-postgres.sql");
        return this;
    }

    void runSchema(String resource) {
        try (InputStream in = JdbcTaskStore.class.getResourceAsStream(resource)) {
            if (in == null) throw new TaskQueueException("Schema resource missing: " + resource);
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = dataSource.getConnection();
                 var statement = c.createStatement()) {
                statement.execute(ddl);
            }
        } catch (IOException | SQLException e) {
            throw new TaskQueueException("Cannot run schema " + resource + ": " + e.getMessage());
        }
    }

    // ── TaskStore ───────────────────────────────────────────────────────────

    @Override
    public void save(TaskRecord record) {
        String upsert = saveSql();
        withConnection(c -> {
            upsert(c, upsert, record);
            return null;
        });
    }

    /**
     * The zombie guard: a node whose lease was taken over must not overwrite
     * the row — its RUNNING belongs to someone else now.
     */
    private static String saveSql() {
        return "INSERT INTO mc_task (" + COLUMNS + ") VALUES "
                + "(?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?::jsonb,?,?,?,?,?) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "status = EXCLUDED.status, payload = EXCLUDED.payload, "
                + "priority = EXCLUDED.priority, attempt = EXCLUDED.attempt, node_id = EXCLUDED.node_id, "
                // Sticky flag and row-owned mailbox: a save is built from a
                // snapshot, and a cancel or a message that landed since must
                // not be erased by the older picture. Only drainNotifications
                // (via update()) empties the mailbox.
                + "cancel_requested = mc_task.cancel_requested OR EXCLUDED.cancel_requested, "
                + "waiting_for = EXCLUDED.waiting_for, "
                + "notifications = CASE WHEN mc_task.notifications <> '[]'::jsonb "
                + "                     THEN mc_task.notifications ELSE EXCLUDED.notifications END, "
                + "resumed = EXCLUDED.resumed, "
                + "state = EXCLUDED.state, result = EXCLUDED.result, failure = EXCLUDED.failure, "
                + "run_after = EXCLUDED.run_after, started_at = EXCLUDED.started_at, "
                + "ended_at = EXCLUDED.ended_at "
                + "WHERE mc_task.status <> 'RUNNING' OR mc_task.lease_owner = ?";
    }

    /**
     * The one door out of the world: terminal write and wake share a single
     * transaction, so "the child is done" and "the parent knows" cannot come
     * apart — no crash window, no lost wake to sweep up later.
     */
    @Override
    public List<TaskRecord> finish(TaskRecord terminal) {
        String upsert = saveSql();
        return inTransaction(c -> {
            upsert(c, upsert, terminal);
            return wake(c, terminal.id());
        });
    }

    private void upsert(Connection c, String sql, TaskRecord record) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindRecord(ps, record);
            ps.setString(21, nodeId);
            if (ps.executeUpdate() == 0) {
                // The guard refused the write — and the CALLER must know, or
                // it fires wake/onTerminal for a transition that never
                // happened while the real attempt still runs elsewhere.
                throw new LeaseLostException(
                        "task " + record.id() + " is RUNNING under another node's lease");
            }
        }
    }

    @Override
    public Optional<TaskRecord> insertIfAbsent(TaskRecord record) {
        return inTransaction(connection -> {
            // Atomic create-or-nothing: the unique PK arbitrates the race.
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO mc_task (" + COLUMNS + ") VALUES "
                            + "(?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?::jsonb,?,?,?,?,?) "
                            + "ON CONFLICT (id) DO NOTHING")) {
                bindRecord(statement, record);
                if (statement.executeUpdate() == 1) return Optional.<TaskRecord>empty();
            }
            return lockRow(connection, record.id());
        });
    }

    @Override
    public Optional<TaskRecord> find(String id) {
        return withConnection(c -> selectOne(c, "SELECT " + COLUMNS + " FROM mc_task WHERE id = ?",
                ps -> ps.setString(1, id)));
    }

    @Override
    public List<TaskRecord> byStatus(TaskStatus status, int limit) {
        return withConnection(c -> selectMany(c,
                "SELECT " + COLUMNS + " FROM mc_task WHERE status = ? LIMIT ?",
                ps -> {
                    ps.setString(1, status.name());
                    ps.setInt(2, limit);
                }));
    }

    @Override
    public List<TaskRecord> byParent(String id) {
        return withConnection(c -> selectMany(c,
                "SELECT " + COLUMNS + " FROM mc_task WHERE parent_task_id = ? ORDER BY submitted_at",
                ps -> ps.setString(1, id)));
    }

    @Override
    public Optional<TaskRecord> claimNext(Set<String> types) {
        if (types.isEmpty()) return Optional.empty();
        // SKIP LOCKED is the whole trick: twenty dispatchers walk the same
        // index and never fight over a row.
        String sql = "SELECT " + COLUMNS + " FROM mc_task "
                + "WHERE status = 'QUEUED' AND type = ANY(?) "
                + "AND (run_after IS NULL OR run_after <= now()) "
                + "ORDER BY priority DESC, submitted_at "
                + "LIMIT 1 FOR UPDATE SKIP LOCKED";
        return inTransaction(c -> {
            Optional<TaskRecord> next = selectOne(c, sql,
                    ps -> ps.setArray(1, c.createArrayOf("text", types.toArray())));
            if (next.isEmpty()) return Optional.empty();
            TaskRecord claimed = next.get().claimed(nodeId);
            update(c, claimed);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mc_task SET lease_owner = ?, lease_expires_at = now() + ?::interval WHERE id = ?")) {
                ps.setString(1, nodeId);
                ps.setString(2, lease.toMillis() + " milliseconds");
                ps.setString(3, claimed.id());
                ps.executeUpdate();
            }
            return Optional.of(claimed);
        });
    }

    @Override
    public Optional<TaskRecord> requestCancel(String id) {
        return inTransaction(c -> {
            Optional<TaskRecord> current = lockRow(c, id);
            if (current.isEmpty() || current.get().status().terminal()) return Optional.empty();
            TaskRecord record = current.get();
            TaskRecord updated = record.status() == TaskStatus.RUNNING
                    ? record.withCancelRequested()
                    : record.cancelled();
            update(c, updated);
            return Optional.of(updated);
        });
    }

    @Override
    public TaskRecord suspend(String id, TaskOutcome.Suspend suspend) {
        return inTransaction(c -> {
            TaskRecord record = lockRowFenced(c, id)
                    .orElseThrow(() -> new TaskQueueException("Unknown task " + id));
            // A cancel that arrived while we were running must not be slept
            // through: a task asked to die parks nowhere — it dies here.
            if (record.cancelRequested()) {
                TaskRecord cancelled = record.cancelled();
                update(c, cancelled);
                releaseLease(c, id);
                return cancelled;
            }
            // Sorted, so two concurrent suspends over overlapping sets lock
            // in the same global order instead of deadlocking each other.
            Set<String> waitingFor = new java.util.TreeSet<>(suspend.awaitedTaskIds());
            if (suspend.awaitChildren()) {
                for (TaskRecord child : selectMany(c,
                        "SELECT " + COLUMNS + " FROM mc_task WHERE parent_task_id = ?",
                        ps -> ps.setString(1, id))) {
                    waitingFor.add(child.id());
                }
            }
            Set<String> open = new java.util.HashSet<>();
            for (String awaited : waitingFor) {
                // FOR UPDATE: without the lock, an awaited task can turn
                // terminal between this read and our commit — its wake() scans
                // waiting_for BEFORE ours is visible (MVCC) and never finds
                // us. Locking the row serialises us against that transition.
                Optional<TaskRecord> child = selectOne(c,
                        "SELECT " + COLUMNS + " FROM mc_task WHERE id = ? FOR UPDATE",
                        ps -> ps.setString(1, awaited));
                if (child.isEmpty() || !child.get().status().terminal()) open.add(awaited);
            }
            boolean notificationWait = suspend.awaitedTaskIds().isEmpty() && !suspend.awaitChildren();
            TaskRecord updated = record.parkedOn(waitingFor, open, notificationWait);
            update(c, updated);
            return updated;
        });
    }

    @Override
    public Optional<TaskRecord> notify(String id, TaskNotification notification) {
        return inTransaction(c -> {
            Optional<TaskRecord> current = lockRow(c, id);
            if (current.isEmpty() || current.get().status().terminal()) return Optional.empty();
            TaskRecord updated = current.get().withNotification(notification);
            boolean wakes = updated.status() == TaskStatus.SUSPENDED;   // a message always wakes
            if (wakes) updated = updated.requeued();
            update(c, updated);
            return wakes ? Optional.of(updated) : Optional.empty();
        });
    }

    @Override
    public List<TaskNotification> drainNotifications(String id) {
        return inTransaction(c -> {
            Optional<TaskRecord> current = lockRowFenced(c, id);
            if (current.isEmpty() || current.get().notifications().isEmpty()) return List.of();
            update(c, current.get().withDrainedMailbox());
            return current.get().notifications();
        });
    }

    /** Internal since {@code finish}: waking is part of turning terminal. */
    private List<TaskRecord> wake(Connection c, String terminalTaskId) throws SQLException {
        {
            List<TaskRecord> waiting = selectMany(c,
                    "SELECT " + COLUMNS + " FROM mc_task WHERE waiting_for @> to_jsonb(?::text) FOR UPDATE",
                    ps -> ps.setString(1, terminalTaskId));
            List<TaskRecord> requeued = new ArrayList<>();
            for (TaskRecord record : waiting) {
                TaskRecord updated = record.notWaitingFor(terminalTaskId);
                if (record.status() != TaskStatus.SUSPENDED) {
                    // Not parked: only keep the ledger honest, no wake decision.
                    updated = record.withWaitingFor(updated.waitingFor());
                }
                update(c, updated);
                if (record.status() == TaskStatus.SUSPENDED
                        && updated.status() == TaskStatus.QUEUED) requeued.add(updated);
            }
            return requeued;
        }
    }

    @Override
    public void updateState(String id, Map<String, Object> state) {
        withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mc_task SET state = ?::jsonb WHERE id = ? "
                    + "AND (status <> 'RUNNING' OR lease_owner IS NULL OR lease_owner = ?)")) {
                ps.setString(1, Json.write(state));
                ps.setString(2, id);
                ps.setString(3, nodeId);
                if (ps.executeUpdate() == 0 && find(id).isPresent()) {
                    throw new LeaseLostException(
                            "task " + id + " is RUNNING under another node's lease");
                }
            }
            return null;
        });
    }

    /**
     * No-op on purpose: on a shared store, RUNNING rows that are not ours
     * usually belong to LIVE neighbours. Crash recovery is the lease's job —
     * see {@link #recoverExpired}.
     */
    @Override
    public List<TaskRecord> sweepRunning(String reason) {
        return List.of();
    }

    @Override
    public TaskRecord retry(String id, Duration delay, TaskFailure failure) {
        return inTransaction(c -> {
            TaskRecord record = lockRowFenced(c, id)
                    .orElseThrow(() -> new TaskQueueException("Unknown task " + id));
            TaskRecord updated = record.retryAt(Instant.now().plus(delay), failure);
            update(c, updated);
            // The due check runs on the DATABASE clock, so the delay must be
            // measured there too — a drifted app clock must not push retries
            // into the far future (or the past).
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mc_task SET run_after = now() + ?::interval WHERE id = ?")) {
                ps.setString(1, delay.toMillis() + " milliseconds");
                ps.setString(2, id);
                ps.executeUpdate();
            }
            releaseLease(c, id);
            return updated;
        });
    }

    @Override
    public int purgeTerminal(Instant before) {
        // Tree logic in Java, exactly like the in-memory store — a recursive
        // CTE would duplicate the rules for no gain at these volumes.
        return inTransaction(c -> {
            List<TaskRecord> all = selectMany(c, "SELECT " + COLUMNS + " FROM mc_task", ps -> { });
            Map<String, List<String>> childIds = new HashMap<>();
            Map<String, TaskRecord> byId = new HashMap<>();
            List<String> roots = new ArrayList<>();
            for (TaskRecord record : all) byId.put(record.id(), record);
            for (TaskRecord record : all) {
                String parent = record.parentTaskId();
                if (parent == null || !byId.containsKey(parent)) roots.add(record.id());
                else childIds.computeIfAbsent(parent, key -> new ArrayList<>()).add(record.id());
            }
            List<String> doomed = new ArrayList<>();
            for (String root : roots) {
                List<String> tree = new ArrayList<>();
                collectTree(root, childIds, tree);
                if (tree.stream().allMatch(id -> {
                    TaskRecord r = byId.get(id);
                    return r.status().terminal() && r.endedAt() != null && r.endedAt().isBefore(before);
                })) {
                    doomed.addAll(tree);
                }
            }
            if (doomed.isEmpty()) return 0;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mc_task WHERE id = ANY(?)")) {
                ps.setArray(1, c.createArrayOf("text", doomed.toArray()));
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public java.util.Optional<Instant> nextDueAt(Set<String> types) {
        if (types.isEmpty()) return Optional.empty();
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT min(run_after) FROM mc_task WHERE status = 'QUEUED' "
                    + "AND type = ANY(?) AND run_after > now()")) {
                ps.setArray(1, c.createArrayOf("text", types.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp(1);
                        return Optional.ofNullable(ts).map(Timestamp::toInstant);
                    }
                    return Optional.empty();
                }
            }
        });
    }

    @Override
    public boolean renewLease(String id) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mc_task SET lease_expires_at = now() + ?::interval "
                    + "WHERE id = ? AND lease_owner = ? AND status = 'RUNNING' "
                    + "AND lease_expires_at > now()")) {
                ps.setString(1, lease.toMillis() + " milliseconds");
                ps.setString(2, id);
                ps.setString(3, nodeId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<TaskRecord> recoverExpired() {
        List<TaskRecord> reclaimed = inTransaction(c -> {
            List<TaskRecord> expired = selectMany(c,
                    "SELECT " + COLUMNS + " FROM mc_task WHERE status = 'RUNNING' "
                    + "AND lease_expires_at < now() FOR UPDATE SKIP LOCKED",
                    ps -> { });
            List<TaskRecord> result = new ArrayList<>();
            for (TaskRecord record : expired) {
                TaskFailure failure = TaskFailure.of(
                        "lease expired — node stopped renewing", record.attempt());
                TaskRecord updated = record.attempt() < record.maxAttempts()
                        ? record.retryAt(Instant.now(), failure)
                        : record.failed(failure);
                update(c, updated);
                if (!updated.status().terminal()) {
                    // Immediately due — and on the database clock, see retry().
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE mc_task SET run_after = NULL WHERE id = ?")) {
                        ps.setString(1, record.id());
                        ps.executeUpdate();
                    }
                }
                releaseLease(c, record.id());
                result.add(updated);
            }
            return result;
        });
        reclaimed.addAll(recoverLostWakes());
        return reclaimed;
    }

    /**
     * The safety net under every wake that can be lost: a SUSPENDED task whose
     * awaited set holds nothing alive any more is woken here. Waking is
     * event-driven in the happy path ({@code wake()} on each terminal) — but
     * events can be lost to races and crashes, and only a periodic look at
     * the TRUTH turns "should never happen" into "heals within a tick".
     */
    private List<TaskRecord> recoverLostWakes() {
        return inTransaction(c -> {
            List<TaskRecord> parked = selectMany(c,
                    "SELECT " + COLUMNS + " FROM mc_task WHERE status = 'SUSPENDED' "
                    + "AND waiting_for <> '[]'::jsonb FOR UPDATE SKIP LOCKED",
                    ps -> { });
            List<TaskRecord> woken = new ArrayList<>();
            for (TaskRecord record : parked) {
                Set<String> remaining = new java.util.HashSet<>();
                for (String awaited : record.waitingFor()) {
                    Optional<TaskRecord> t = selectOne(c,
                            "SELECT " + COLUMNS + " FROM mc_task WHERE id = ?",
                            ps -> ps.setString(1, awaited));
                    if (t.isPresent() && !t.get().status().terminal()) remaining.add(awaited);
                }
                if (remaining.size() == record.waitingFor().size()) continue;
                TaskRecord updated = record.withWaitingFor(remaining);
                if (remaining.isEmpty()) {
                    updated = updated.requeued();
                    woken.add(updated);
                    log.warn("Recovered lost wake: task {} ({}) was suspended on "
                            + "nothing alive — requeued", record.id(), record.type());
                }
                update(c, updated);
            }
            return woken;
        });
    }

    /** Row counts per status in one query — dashboard numbers, not a port concern. */
    public Map<TaskStatus, Integer> countByStatus() {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status, count(*) FROM mc_task GROUP BY status")) {
                try (ResultSet rs = ps.executeQuery()) {
                    Map<TaskStatus, Integer> counts = new HashMap<>();
                    while (rs.next()) {
                        counts.put(TaskStatus.valueOf(rs.getString(1)), rs.getInt(2));
                    }
                    return counts;
                }
            }
        });
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private Optional<TaskRecord> lockRow(Connection c, String id) throws SQLException {
        return selectOne(c, "SELECT " + COLUMNS + " FROM mc_task WHERE id = ? FOR UPDATE",
                ps -> ps.setString(1, id));
    }

    /**
     * Locks the row AND enforces the fence: a RUNNING row under another
     * node's live lease belongs to that node — every transition a stale
     * attempt would write here (retry, suspend, drain, state) must be
     * refused, or two nodes end up running the same task.
     */
    private Optional<TaskRecord> lockRowFenced(Connection c, String id) throws SQLException {
        Optional<TaskRecord> record = lockRow(c, id);
        if (record.isEmpty()) return record;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lease_owner FROM mc_task WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String owner = rs.getString(1);
                if (record.get().status() == TaskStatus.RUNNING
                        && owner != null && !owner.equals(nodeId)) {
                    throw new LeaseLostException(
                            "task " + id + " is RUNNING under lease of " + owner);
                }
            }
        }
        return record;
    }

    private void update(Connection c, TaskRecord record) throws SQLException {
        String sql = "UPDATE mc_task SET type=?, status=?, payload=?::jsonb, priority=?, "
                + "parent_task_id=?, attempt=?, node_id=?, cancel_requested=?, waiting_for=?::jsonb, "
                + "notifications=?::jsonb, resumed=?, state=?::jsonb, result=?, failure=?::jsonb, "
                + "run_after=?, max_attempts=?, submitted_at=?, started_at=?, ended_at=? "
                + "WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, record.type());
            ps.setString(2, record.status().name());
            ps.setString(3, Json.write(record.payload()));
            ps.setInt(4, record.priority());
            ps.setString(5, record.parentTaskId());
            ps.setInt(6, record.attempt());
            ps.setString(7, record.nodeId());
            ps.setBoolean(8, record.cancelRequested());
            ps.setString(9, Json.write(record.waitingFor()));
            ps.setString(10, Json.write(record.notifications()));
            ps.setBoolean(11, record.resumed());
            ps.setString(12, Json.write(record.state()));
            ps.setString(13, record.result());
            ps.setString(14, Json.write(record.failure()));
            ps.setTimestamp(15, timestamp(record.runAfter()));
            ps.setInt(16, record.maxAttempts());
            ps.setTimestamp(17, timestamp(record.submittedAt()));
            ps.setTimestamp(18, timestamp(record.startedAt()));
            ps.setTimestamp(19, timestamp(record.endedAt()));
            ps.setString(20, record.id());
            ps.executeUpdate();
        }
    }

    private void bindRecord(PreparedStatement ps, TaskRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.type());
        ps.setString(3, record.status().name());
        ps.setString(4, Json.write(record.payload()));
        ps.setInt(5, record.priority());
        ps.setString(6, record.parentTaskId());
        ps.setInt(7, record.attempt());
        ps.setString(8, record.nodeId());
        ps.setBoolean(9, record.cancelRequested());
        ps.setString(10, Json.write(record.waitingFor()));
        ps.setString(11, Json.write(record.notifications()));
        ps.setBoolean(12, record.resumed());
        ps.setString(13, Json.write(record.state()));
        ps.setString(14, record.result());
        ps.setString(15, Json.write(record.failure()));
        ps.setTimestamp(16, timestamp(record.runAfter()));
        ps.setInt(17, record.maxAttempts());
        ps.setTimestamp(18, timestamp(record.submittedAt()));
        ps.setTimestamp(19, timestamp(record.startedAt()));
        ps.setTimestamp(20, timestamp(record.endedAt()));
    }

    private void releaseLease(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE mc_task SET lease_owner = NULL, lease_expires_at = NULL WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private static TaskRecord read(ResultSet rs) throws SQLException {
        return new TaskRecord(
                rs.getString("id"),
                rs.getString("type"),
                TaskStatus.valueOf(rs.getString("status")),
                Json.readMap(rs.getString("payload")),
                rs.getInt("priority"),
                rs.getString("parent_task_id"),
                rs.getInt("attempt"),
                rs.getString("node_id"),
                rs.getBoolean("cancel_requested"),
                Json.readStringSet(rs.getString("waiting_for")),
                Json.readNotifications(rs.getString("notifications")),
                rs.getBoolean("resumed"),
                Json.readMap(rs.getString("state")),
                rs.getString("result"),
                Json.readFailure(rs.getString("failure")),
                instant(rs.getTimestamp("run_after")),
                rs.getInt("max_attempts"),
                instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("ended_at")));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static Optional<TaskRecord> selectOne(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static List<TaskRecord> selectMany(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskRecord> result = new ArrayList<>();
                while (rs.next()) result.add(read(rs));
                return result;
            }
        }
    }

    private <T> T withConnection(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new TaskQueueException("Store operation failed: " + e.getMessage());
        }
    }

    private <T> T inTransaction(Work<T> work) {
        // Deadlocks between finish (child→parents) and suspend (parent→
        // children) are inherent: both lock orders are semantically forced.
        // Postgres detects the cycle and aborts one loser — which makes it a
        // plain serialization conflict, and the store's job is to retry it,
        // not the caller's to know about it.
        for (int attempt = 1; ; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                boolean retryable = "40P01".equals(e.getSQLState())    // deadlock detected
                        || "40001".equals(e.getSQLState());            // serialization failure
                if (retryable && attempt < 3) {
                    log.debug("Transaction serialization conflict (attempt {}), retrying: {}",
                            attempt, e.getMessage());
                    try {
                        Thread.sleep(20L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TaskQueueException("Interrupted during transaction retry");
                    }
                    continue;
                }
                throw new TaskQueueException("Store transaction failed: " + e.getMessage());
            }
        }
    }

    private static void collectTree(String id, Map<String, List<String>> childIds, List<String> into) {
        into.add(id);
        for (String child : childIds.getOrDefault(id, List.of())) {
            collectTree(child, childIds, into);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
