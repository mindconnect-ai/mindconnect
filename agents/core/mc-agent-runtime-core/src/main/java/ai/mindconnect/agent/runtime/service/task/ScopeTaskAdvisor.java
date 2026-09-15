package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskSubmission;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
 */
public final class ScopeTaskAdvisor implements TaskAdvisor {

    /** Payload key of the namespace — reserved for the advisor. */
    public static final String NAMESPACE = "mc.namespace";
    /** Payload key of the user behind the work, present only when the scope had one. */
    public static final String USER = "mc.user";

    private final ScopeSupplier submitting;
    private final ThreadBoundScope executing;

    /**
     * @param submitting whose scope a submission is stamped with
     * @param executing  what an execution is bound to
     */
    public ScopeTaskAdvisor(ScopeSupplier submitting, ThreadBoundScope executing) {
        this.submitting = Objects.requireNonNull(submitting, "submitting");
        this.executing = Objects.requireNonNull(executing, "executing");
    }

    @Override
    public TaskSubmission beforeSubmit(TaskSubmission submission) {
        if (submission.payload().containsKey(NAMESPACE)) return submission;
        Scope scope = submitting.get();
        Map<String, Object> payload = new HashMap<>(submission.payload());
        payload.put(NAMESPACE, scope.namespace().value());
        scope.userIfAny().ifPresent(user -> payload.put(USER, user.value()));
        return new TaskSubmission(submission.type(), payload, submission.priority(), submission.parentTaskId(),
                submission.runAfter(), submission.maxAttempts(), submission.id());
    }

    @Override
    public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
        return executing.callIn(scopeOf(ctx), () -> chain.proceed(ctx));
    }

    /** Right inside the MDC advisor: everything that touches a store runs bound. */
    @Override
    public int order() {
        return Integer.MIN_VALUE + 2;
    }

    /** The scope a task was submitted in, read back from its payload. */
    public static Scope scopeOf(TaskContext ctx) {
        Map<String, Object> payload = ctx.task().payload();
        Object namespace = payload.get(NAMESPACE);
        if (namespace == null) {
            throw new IllegalStateException("Task " + ctx.task().id() + " (" + ctx.task().type()
                    + ") carries no namespace — it was submitted past the scope advisor");
        }
        Object user = payload.get(USER);
        return Scope.of(new Namespace(namespace.toString()), user == null ? null : UserId.of(user.toString()));
    }
}
