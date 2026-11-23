package ai.mindconnect.agent.runtime.engine.service;

import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskWorker;

/**
 * Runs a task in the namespace it was submitted in. A task runs on a
 * worker's thread, maybe in another process, where nobody is "current":
 * the namespace travels in the task's payload and is bound here, around
 * everything the worker does — stores, resolvers and hooks included.
 */
final class NamespaceBoundWorker implements TaskWorker {

    /** The payload key every task of the engine carries its namespace under. */
    static final String NAMESPACE = "namespace";

    private final TaskWorker worker;
    private final NamespaceProvider namespaces;

    NamespaceBoundWorker(TaskWorker worker, NamespaceProvider namespaces) {
        this.worker = worker;
        this.namespaces = namespaces;
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) throws Exception {
        if (!(ctx.task().payload().get(NAMESPACE) instanceof String namespace)) {
            throw new IllegalStateException("Task " + ctx.task().id() + " carries no namespace");
        }
        // The provider's body cannot throw a checked exception; the worker's is carried out and thrown again.
        Object result = namespaces.callIn(namespace, () -> {
            try {
                return worker.execute(ctx);
            } catch (Exception e) {
                return e;
            }
        });
        if (result instanceof Exception failure) {
            throw failure;
        }
        return (TaskOutcome) result;
    }
}
