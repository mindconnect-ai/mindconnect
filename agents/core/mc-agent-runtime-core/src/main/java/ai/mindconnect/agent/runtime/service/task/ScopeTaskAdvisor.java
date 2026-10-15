package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskSubmission;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries the submitter's {@link Scope} on every task and binds it again
 * around every execution — the first delivery, every retry and every
 * wake-up after a suspend, which may happen hours later in another process.
 * A task runs on its own virtual thread, so nothing is inherited; only the
 * payload survives, and that is where the scope travels.
 *
 * <p>Registered once on the queue; workers and the places that submit stay
 * as they are. A child task (the tool call a turn submits, the sub-agent a
 * tool starts) is submitted from inside a bound execution, so it inherits
 * its parent's scope through {@link #beforeSubmit} — and a submission that
 * already names a namespace keeps it: a child never moves out of its
 * parent's namespace.
 *
 * <p>A runtime fixed to one namespace has its tasks stamped all the same, so
 * every task record names its namespace; there is nothing to bind there, and
 * a task runs as it is — one submitted before it was stamped included.
 */
public final class ScopeTaskAdvisor implements TaskAdvisor {

    /** Payload key of the namespace — reserved for the advisor. */
    public static final String NAMESPACE = "mc.namespace";
    /** Payload key of the user behind the work, present only when the scope had one. */
    public static final String USER = "mc.user";

    private final ScopeSupplier scope;

    /**
     * @param scope whose scope a submission is stamped with, and what an
     *              execution runs in again ({@link ScopeSupplier#callIn})
     */
    public ScopeTaskAdvisor(ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public TaskSubmission beforeSubmit(TaskSubmission submission) {
        if (submission.payload().containsKey(NAMESPACE)) return submission;
        Scope submitter = scope.get();
        Map<String, Object> payload = new HashMap<>(submission.payload());
        payload.put(NAMESPACE, submitter.namespace().value());
        submitter.userIfAny().ifPresent(user -> payload.put(USER, user.value()));
        return new TaskSubmission(submission.type(), payload, submission.priority(), submission.parentTaskId(),
                submission.runAfter(), submission.maxAttempts(), submission.id());
    }

    @Override
    public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
        if (!scope.bindsPerThread()) return chain.proceed(ctx);
        Scope submitted = scopeIfAny(ctx.task()).orElseThrow(() -> new IllegalStateException("Task "
                + ctx.task().id() + " (" + ctx.task().type() + ") carries no namespace — it was submitted past the scope advisor"));
        return scope.callIn(submitted, () -> chain.proceed(ctx));
    }

    /** Right inside the MDC advisor: everything that touches a store runs bound. */
    @Override
    public int order() {
        return Integer.MIN_VALUE + 2;
    }

    /** The scope a task record carries, if it was stamped — a monitor reading tasks of every namespace asks this. */
    public static Optional<Scope> scopeIfAny(TaskRecord task) {
        Map<String, Object> payload = task.payload();
        Object namespace = payload.get(NAMESPACE);
        if (namespace == null) return Optional.empty();
        Object user = payload.get(USER);
        return Optional.of(Scope.of(new Namespace(namespace.toString()), user == null ? null : UserId.of(user.toString())));
    }
}
