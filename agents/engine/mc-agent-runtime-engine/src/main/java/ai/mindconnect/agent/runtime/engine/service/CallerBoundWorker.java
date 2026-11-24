package ai.mindconnect.agent.runtime.engine.service;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.UserProvider;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskWorker;

/**
 * Runs a task as whoever submitted it: in their namespace and as the user
 * who acted. A task runs on a worker's thread, maybe in another process,
 * where nobody is "current": both travel in the task's payload and are
 * bound here, around everything the worker does — stores, resolvers and
 * hooks included.
 */
final class CallerBoundWorker implements TaskWorker {

    /** The payload key every task of the engine carries its namespace under. */
    static final String NAMESPACE = "namespace";
    /** The payload key of the user who acted when the task's turn was submitted; absent where nobody was known. */
    static final String USER = "user";

    private final TaskWorker worker;
    private final NamespaceProvider namespaces;
    private final UserProvider users;

    CallerBoundWorker(TaskWorker worker, NamespaceProvider namespaces, UserProvider users) {
        this.worker = worker;
        this.namespaces = namespaces;
        this.users = users;
    }

    /** Who is submitting right now, as the entries a new task's payload carries. */
    static Map<String, Object> caller(NamespaceProvider namespaces, UserProvider users) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put(NAMESPACE, namespaces.current());
        users.current().ifPresent(user -> caller.put(USER, user.value()));
        return caller;
    }

    /** The caller of a task, for the tasks it submits: a tool call and a sub-agent's turn act as their turn does. */
    static Map<String, Object> callerOf(TaskRecord task) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put(NAMESPACE, task.payload().get(NAMESPACE));
        if (task.payload().get(USER) != null) {
            caller.put(USER, task.payload().get(USER));
        }
        return caller;
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) throws Exception {
        if (!(ctx.task().payload().get(NAMESPACE) instanceof String namespace)) {
            throw new IllegalStateException("Task " + ctx.task().id() + " carries no namespace");
        }
        // The providers' bodies cannot throw a checked exception; the worker's is carried out and thrown again.
        Object result = namespaces.callIn(namespace, () -> ctx.task().payload().get(USER) instanceof String user
                ? users.callAs(UserId.of(user), () -> run(ctx))
                : run(ctx));
        if (result instanceof Exception failure) {
            throw failure;
        }
        return (TaskOutcome) result;
    }

    private Object run(TaskContext ctx) {
        try {
            return worker.execute(ctx);
        } catch (Exception e) {
            return e;
        }
    }
}
