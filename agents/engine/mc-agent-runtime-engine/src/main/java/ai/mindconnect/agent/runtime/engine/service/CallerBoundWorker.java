package ai.mindconnect.agent.runtime.engine.service;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.ScopeSupplier;
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
    /**
     * The payload key of what else the caller's scope carried — a host's own
     * attributes, such as the organisation a user works in; absent where
     * there were none. Text by text, so a task store keeps them as they are.
     */
    static final String ATTRIBUTES = "attributes";

    private final TaskWorker worker;
    private final ScopeSupplier scope;

    CallerBoundWorker(TaskWorker worker, ScopeSupplier scope) {
        this.worker = worker;
        this.scope = scope;
    }

    /** Who is submitting right now, as the entries a new task's payload carries. */
    static Map<String, Object> caller(ScopeSupplier scope) {
        Map<String, Object> caller = new LinkedHashMap<>();
        Scope current = scope.get();
        caller.put(NAMESPACE, current.namespace().value());
        current.userIfAny().ifPresent(user -> caller.put(USER, user.value()));
        if (!current.attributes().isEmpty()) {
            caller.put(ATTRIBUTES, Map.copyOf(current.attributes()));
        }
        return caller;
    }

    /** The caller of a task, for the tasks it submits: a tool call and a sub-agent's turn act as their turn does. */
    static Map<String, Object> callerOf(TaskRecord task) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put(NAMESPACE, task.payload().get(NAMESPACE));
        if (task.payload().get(USER) != null) {
            caller.put(USER, task.payload().get(USER));
        }
        if (task.payload().get(ATTRIBUTES) != null) {
            caller.put(ATTRIBUTES, task.payload().get(ATTRIBUTES));
        }
        return caller;
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) throws Exception {
        if (!(ctx.task().payload().get(NAMESPACE) instanceof String namespace)) {
            throw new IllegalStateException("Task " + ctx.task().id() + " carries no namespace");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (ctx.task().payload().get(ATTRIBUTES) instanceof Map<?, ?> carried) {
            carried.forEach((key, value) -> attributes.put(String.valueOf(key), String.valueOf(value)));
        }
        Scope caller = new Scope(new Namespace(namespace),
                ctx.task().payload().get(USER) instanceof String user ? UserId.of(user) : null, attributes);
        return scope.callIn(caller, () -> worker.execute(ctx));
    }
}
