package ai.mindconnect.agent.runtime.service.approval;

import ai.mindconnect.agent.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ToolApprovalStoreTest {

    private final SessionId root = SessionId.random();
    private final SessionId origin = SessionId.random();
    private final ToolApprovalStore store = new ToolApprovalStore();

    private ToolApproval approval(String callId, Instant at) {
        return new ToolApproval("req-" + callId, callId, "web_search",
                "{\"name\":\"web_search\",\"arguments\":{}}", origin, root, "task_tool_x_" + callId, at);
    }

    @Test
    void registeringIsIdempotentPerChatAndCallId() {
        assertThat(store.saveIfAbsent(approval("c1", Instant.EPOCH))).isTrue();
        assertThat(store.saveIfAbsent(approval("c1", Instant.EPOCH))).isFalse();
        assertThat(store.find(root, "c1")).isPresent();
    }

    @Test
    void openCardsOfOneRootComeOldestFirst() {
        store.saveIfAbsent(approval("newer", Instant.parse("2026-01-02T00:00:00Z")));
        store.saveIfAbsent(approval("older", Instant.parse("2026-01-01T00:00:00Z")));
        ToolApproval foreign = new ToolApproval("r", "c-foreign", "t", "{}",
                origin, SessionId.random(), "task", Instant.EPOCH);
        store.saveIfAbsent(foreign);

        assertThat(store.openForRoot(root))
                .extracting(ToolApproval::callId)
                .containsExactly("older", "newer");
    }

    @Test
    void answeringDeletesExactlyTheOneQuestion() {
        store.saveIfAbsent(approval("c1", Instant.EPOCH));
        store.saveIfAbsent(approval("c2", Instant.EPOCH));
        store.delete(root, "c1");
        assertThat(store.find(root, "c1")).isEmpty();
        assertThat(store.find(root, "c2")).isPresent();
    }

    @Test
    void theSameCallIdInTwoChatsIsTwoQuestions() {
        SessionId otherRoot = SessionId.random();
        assertThat(store.saveIfAbsent(approval("call_1", Instant.EPOCH))).isTrue();
        assertThat(store.saveIfAbsent(new ToolApproval("req-other", "call_1", "web_search", "{}",
                SessionId.random(), otherRoot, "task_other", Instant.EPOCH))).isTrue();

        assertThat(store.find(root, "call_1")).map(ToolApproval::toolTaskId).contains("task_tool_x_call_1");
        assertThat(store.find(otherRoot, "call_1")).map(ToolApproval::toolTaskId).contains("task_other");
    }

    @Test
    void aCallIdAnsweredThroughAnotherChatFindsAndDeletesNothing() {
        store.saveIfAbsent(approval("c1", Instant.EPOCH));
        SessionId otherRoot = SessionId.random();

        assertThat(store.find(otherRoot, "c1")).isEmpty();
        store.delete(otherRoot, "c1");
        assertThat(store.find(root, "c1")).isPresent();
    }

    @Test
    void cleanupByRootAndBySessionHitsBothAnchors() {
        store.saveIfAbsent(approval("c1", Instant.EPOCH));
        store.deleteForRoot(root);
        assertThat(store.openForRoot(root)).isEmpty();

        store.saveIfAbsent(approval("c2", Instant.EPOCH));
        store.deleteForSession(origin);   // matches the ORIGIN anchor
        assertThat(store.find(root, "c2")).isEmpty();
    }
}
