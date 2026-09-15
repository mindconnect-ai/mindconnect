package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskSubmission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeTaskAdvisorTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final UserId DAVID = UserId.of("david");

    private final ThreadBoundScope bound = ThreadBoundScope.strict();

    @Test
    void stampsTheSubmittersNamespaceAndUser() {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(Scope.of(ACME, DAVID)), bound);

        TaskSubmission stamped = advisor.beforeSubmit(TaskSubmission.of("agent.turn", Map.of("sessionId", "s1")));

        assertThat(stamped.payload()).containsEntry("sessionId", "s1")
                .containsEntry(ScopeTaskAdvisor.NAMESPACE, "acme")
                .containsEntry(ScopeTaskAdvisor.USER, "david");
    }

    @Test
    void leavesTheUserOutWhenTheScopeHasNone() {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(ACME), bound);

        TaskSubmission stamped = advisor.beforeSubmit(TaskSubmission.of("agent.turn", Map.of()));

        assertThat(stamped.payload()).containsEntry(ScopeTaskAdvisor.NAMESPACE, "acme")
                .doesNotContainKey(ScopeTaskAdvisor.USER);
    }

    @Test
    void keepsANamespaceTheSubmissionAlreadyNames() {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(ACME), bound);

        TaskSubmission stamped = advisor.beforeSubmit(
                TaskSubmission.of("agent.tool", Map.of(ScopeTaskAdvisor.NAMESPACE, "parent")));

        assertThat(stamped.payload()).containsEntry(ScopeTaskAdvisor.NAMESPACE, "parent");
    }

    @Test
    void stampsWhateverIsBoundAtSubmitTime() {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(bound, bound);

        TaskSubmission stamped = bound.runIn(Scope.of(ACME),
                () -> advisor.beforeSubmit(TaskSubmission.of("agent.turn", Map.of())));

        assertThat(stamped.payload()).containsEntry(ScopeTaskAdvisor.NAMESPACE, "acme");
    }

    @Test
    void bindsTheStampedScopeAroundTheExecution() throws Exception {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(ACME), bound);
        TaskSubmission stamped = advisor.beforeSubmit(TaskSubmission.of("agent.turn", Map.of()));
        AtomicReference<Scope> seen = new AtomicReference<>();

        TaskOutcome outcome = advisor.aroundExecute(contextOf(stamped), ctx -> {
            seen.set(bound.get());
            return TaskOutcome.done("ok");
        });

        assertThat(outcome).isEqualTo(TaskOutcome.done("ok"));
        assertThat(seen.get().namespace()).isEqualTo(ACME);
        assertThat(seen.get().userIfAny()).isEmpty();
        assertThat(bound.isBound()).isFalse();
    }

    @Test
    void bindsTheUserToo() throws Exception {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(Scope.of(ACME, DAVID)), bound);
        TaskSubmission stamped = advisor.beforeSubmit(TaskSubmission.of("agent.turn", Map.of()));
        AtomicReference<Scope> seen = new AtomicReference<>();

        advisor.aroundExecute(contextOf(stamped), ctx -> {
            seen.set(bound.get());
            return TaskOutcome.done("ok");
        });

        assertThat(seen.get().userIfAny()).contains(DAVID);
    }

    @Test
    void refusesATaskWithoutANamespace() {
        ScopeTaskAdvisor advisor = new ScopeTaskAdvisor(ScopeSupplier.fixed(ACME), bound);

        assertThatThrownBy(() -> advisor.aroundExecute(contextOf(TaskSubmission.of("agent.turn", Map.of())),
                ctx -> TaskOutcome.done("ok")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries no namespace");
    }

    private static TaskContext contextOf(TaskSubmission submission) {
        TaskRecord record = TaskRecord.queued("t1", submission);
        return new TaskContext() {
            @Override public TaskRecord task() { return record; }
            @Override public boolean cancelRequested() { return false; }
            @Override public void onCancel(Runnable hook) { }
            @Override public Map<String, Object> state() { return Map.of(); }
            @Override public void updateState(Map<String, Object> state) { }
            @Override public String submitChild(TaskSubmission s) { throw new UnsupportedOperationException(); }
            @Override public String submitChild(String type, Map<String, Object> payload) { throw new UnsupportedOperationException(); }
            @Override public List<TaskRecord> children() { return List.of(); }
            @Override public List<TaskNotification> notifications() { return List.of(); }
            @Override public boolean notifyTask(String taskId, Map<String, Object> payload) { return false; }
            @Override public boolean notifyParent(Map<String, Object> payload) { return false; }
        };
    }
}
