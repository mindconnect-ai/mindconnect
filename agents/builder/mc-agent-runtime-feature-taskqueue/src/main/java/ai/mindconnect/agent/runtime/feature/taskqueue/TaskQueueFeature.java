package ai.mindconnect.agent.runtime.feature.taskqueue;

import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.FeatureException;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.taskqueue.LoggingTaskListener;
import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.taskqueue.jdbc.JdbcTaskStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

import java.time.Duration;

/**
 * The queue every turn and tool call runs on, configured. Without this
 * feature the core runs an in-process queue over an in-memory store that
 * forgets finished task trees at once; with it: how long finished trees stay
 * readable, how often the maintenance loop runs, and — on Postgres
 * persistence — a {@link JdbcTaskStore} that several nodes share, each
 * claiming with a lease.
 */
public class TaskQueueFeature extends ConfigurableFeature {

    private Duration retention = Duration.ZERO;
    private boolean retentionSet;
    private Duration maintenanceInterval;
    private boolean jdbc;
    private String nodeId;
    private Duration lease = Duration.ofSeconds(30);

    /** How long finished task trees stay readable; {@code null} keeps them for the life of the process. */
    public TaskQueueFeature retention(Duration keepFinished) {
        changing();
        this.retention = keepFinished;
        this.retentionSet = true;
        return this;
    }

    public TaskQueueFeature maintenanceInterval(Duration interval) {
        changing();
        this.maintenanceInterval = interval;
        return this;
    }

    /** The store in the runtime's Postgres, shared by every node; an error on file or in-memory persistence. */
    public TaskQueueFeature jdbc() {
        changing();
        this.jdbc = true;
        return this;
    }

    /** This node's name on a shared store (default: host and process). */
    public TaskQueueFeature nodeId(String nodeId) {
        changing();
        this.nodeId = nodeId;
        return this;
    }

    /** How long a claim on a shared store holds before another node may take the task over. */
    public TaskQueueFeature lease(Duration lease) {
        changing();
        this.lease = lease;
        return this;
    }

    @Override
    public String name() {
        return "task-queue";
    }

    @Override
    protected void install(FeatureContext ctx) {
        ctx.bean(TaskStore.class, () -> {
            if (!jdbc) return new InMemoryTaskStore();
            if (!(ctx.persistence() instanceof Persistence.Postgres postgres)) {
                throw new FeatureException("TaskQueueFeature.jdbc() needs Postgres persistence; this runtime has "
                        + ctx.persistence().getClass().getSimpleName());
            }
            return new JdbcTaskStore(postgres.dataSource(), nodeId != null ? nodeId : defaultNodeId(), lease).initSchema();
        });
        ctx.bean(TaskQueue.class, () -> {
            // Unbounded on purpose: a bounded worker pool deadlocks as soon as a parent task awaits a
            // child, and a sub-agent call does exactly that. Limits belong on the resources (an LLM
            // semaphore), not on the workers — see the LocalTaskQueue javadoc.
            LocalTaskQueue queue = new LocalTaskQueue(ctx.require(TaskStore.class));
            // A failed task would otherwise leave no trace but a tool result saying so.
            queue.addListener(LoggingTaskListener.failuresOnly());
            queue.withRetention(retentionOf(ctx));
            if (maintenanceInterval != null) queue.withMaintenanceInterval(maintenanceInterval);
            // Every task carries the scope it was submitted in, is audited, … — whatever the features contributed.
            ctx.runtime().beans().all(TaskAdvisor.class).forEach(queue::addAdvisor);
            return queue;
        });
    }

    /** What the feature was told, or what the builder's {@code taskRetention(...)} published as a property. */
    private Duration retentionOf(FeatureContext ctx) {
        if (retentionSet) return retention;
        return ctx.property("taskRetention")
                .map(value -> "keep".equals(value) ? null : Duration.parse(value))
                .orElse(retention);
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "node";
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
