package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.service.TaskMonitor.Snapshot;
import ai.mindconnect.adminui.service.TaskMonitor.TaskView;
import ai.mindconnect.taskqueue.TaskFailure;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task manager is rendered per viewer: the tree is the same for
 * everyone, the Cancel buttons are not. This pins the ownership rule, the
 * tree shape (children under their parent) and the badge wording — and that
 * the ids the live stream patches are the ids the components render with.
 */
class TaskMonitorComponentTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
    private static final UUID SESSION = UUID.randomUUID();

    private static TaskRecord queued(String id, String type, String parent) {
        return TaskRecord.queued(id, TaskSubmission.of(type, Map.of("sessionId", SESSION.toString()))
                .withParent(parent));
    }

    private static TaskView view(TaskRecord task, String label, String owner) {
        return new TaskView(task, label, null, owner, SESSION);
    }

    private static Snapshot board() {
        TaskRecord turn = queued("task_turn_1", "agent.turn", null).claimed("node-1");
        TaskRecord tool = queued("task_tool_1_a", "agent.tool", "task_turn_1").claimed("node-1");
        TaskRecord other = queued("task_turn_2", "agent.turn", null);
        TaskRecord failed = queued("task_turn_0", "agent.turn", null).claimed("node-1")
                .failed(TaskFailure.of("boom", 1));
        return new Snapshot(
                List.of(view(turn, "Scout", "alice"), view(tool, "web_search", "alice"), view(other, "Scout", "bob")),
                List.of(view(failed, "Scout", "alice")),
                NOW);
    }

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void cancelIsOfferedForOwnLiveTasksOnly() throws Exception {
        String alice = json(TaskMonitorComponent.body(board(), "alice"));
        assertThat(alice).contains(TaskMonitorComponent.cancelUrl("task_turn_1"));
        assertThat(alice).contains(TaskMonitorComponent.cancelUrl("task_tool_1_a"));
        assertThat(alice).doesNotContain(TaskMonitorComponent.cancelUrl("task_turn_2"));
        // finished: nothing left to cancel, whoever looks
        assertThat(alice).doesNotContain(TaskMonitorComponent.cancelUrl("task_turn_0"));

        String bob = json(TaskMonitorComponent.body(board(), "bob"));
        assertThat(bob).contains(TaskMonitorComponent.cancelUrl("task_turn_2"));
        assertThat(bob).doesNotContain(TaskMonitorComponent.cancelUrl("task_turn_1"));
    }

    @Test
    void aCancelAlreadyRequestedIsNotOfferedAgain() {
        TaskRecord cancelling = queued("t", "agent.turn", null).claimed("n").withCancelRequested();
        assertThat(TaskMonitor.mayCancel(view(cancelling, "Scout", "alice"), "alice")).isFalse();
        assertThat(TaskMonitor.mayCancel(view(queued("t", "agent.turn", null), "Scout", null), "alice"))
                .as("a task without a resolvable owner belongs to nobody")
                .isFalse();
    }

    @Test
    void toolCallsNestUnderTheirTurn() throws Exception {
        String body = json(TaskMonitorComponent.body(board(), "alice"));
        // The tool's node is inside the turn's node, so it appears after it
        // and before the second root.
        int turn = body.indexOf("\"id\":\"task-task_turn_1\"");
        int tool = body.indexOf("\"id\":\"task-task_tool_1_a\"");
        int other = body.indexOf("\"id\":\"task-task_turn_2\"");
        assertThat(turn).isLessThan(tool);
        assertThat(tool).isLessThan(other);
        assertThat(body).contains("\"id\":\"" + TaskMonitorComponent.BODY_ID + "\"");
        assertThat(body).contains("boom");
    }

    @Test
    void badgeCountsRunningAndWaiting() throws Exception {
        String busy = json(TaskMonitorComponent.badge(board()));
        assertThat(busy).contains("\"label\":\"2 running · 1 waiting\"");
        assertThat(busy).contains("is-busy");
        assertThat(busy).contains("\"id\":\"" + TaskMonitor.CHANNEL_ID + "\"");
        assertThat(busy).contains("\"url\":\"" + TaskMonitorComponent.OPEN_URL + "\"");
        assertThat(busy).contains("\"id\":\"" + TaskMonitor.CHANNEL_ID + "-link\"");

        String idle = json(TaskMonitorComponent.badge(new Snapshot(List.of(), List.of(), NOW)));
        assertThat(idle).contains("\"label\":\"idle\"");
        assertThat(idle).doesNotContain("is-busy");
    }

    @Test
    void theLivePatchTargetsTheBadgeAndTheDialogBody() throws Exception {
        String patch = json(TaskMonitorComponent.livePatch(board(), "alice"));
        assertThat(patch).contains("\"targetId\":\"" + TaskMonitor.CHANNEL_ID + "\"");
        assertThat(patch).contains("\"targetId\":\"" + TaskMonitorComponent.BODY_ID + "\"");
    }

    @Test
    void elapsedReadsLikeAClock() {
        TaskRecord waiting = queued("t", "agent.turn", null);
        assertThat(TaskMonitorComponent.elapsed(waiting, waiting.submittedAt().plusSeconds(5))).isEqualTo("waiting 5s");
        TaskRecord running = waiting.claimed("n");
        assertThat(TaskMonitorComponent.elapsed(running, running.startedAt().plusSeconds(125))).isEqualTo("2m 05s");
        assertThat(TaskMonitorComponent.elapsed(running, running.startedAt().plusSeconds(3725))).isEqualTo("1h 02m");
        assertThat(running.status()).isEqualTo(TaskStatus.RUNNING);
    }
}
