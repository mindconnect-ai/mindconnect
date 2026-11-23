package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.port.out.BackgroundWork;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWorker;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.TaskWorker;

/**
 * Background work as tasks: as durable as the queue is, retried the way the
 * queue retries. Both sides in one class because they are one agreement —
 * what a submission puts into a task is what the worker reads out of it.
 */
final class QueuedBackgroundWork implements BackgroundWork, TaskWorker {

    static final String TYPE = "background-work";
    private static final String WORKER = "worker";
    private static final String PAYLOAD = "payload";

    private final TaskQueue queue;
    private final Supplier<List<BackgroundWorker>> workers;
    private final NamespaceProvider namespaces;

    QueuedBackgroundWork(TaskQueue queue, Supplier<List<BackgroundWorker>> workers, NamespaceProvider namespaces) {
        this.queue = queue;
        this.namespaces = namespaces;
        this.workers = workers;
    }

    @Override
    public void submit(String workerName, Map<String, Object> payload) {
        queue.submit(TaskSubmission.of(TYPE, Map.of(
                NamespaceBoundWorker.NAMESPACE, namespaces.current(), WORKER, workerName, PAYLOAD, payload)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome execute(TaskContext ctx) {
        String name = (String) ctx.task().payload().get(WORKER);
        BackgroundWorker worker = workers.get().stream().filter(known -> known.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("There is no background worker " + name));
        worker.run((Map<String, Object>) ctx.task().payload().get(PAYLOAD));
        return TaskOutcome.done("");
    }
}
