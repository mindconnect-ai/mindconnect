package ai.mindconnect.agent.service.approval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolApprovalStoreTest {

    private final UUID root = UUID.randomUUID();
    private final UUID origin = UUID.randomUUID();
    private final ToolApprovalStore store = new ToolApprovalStore();

    private ToolApproval approval(String callId, Instant at) {
        return new ToolApproval("req-" + callId, callId, "web_search",
                "{\"name\":\"web_search\",\"arguments\":{}}", origin, root, "task_tool_x_" + callId, at);
    }

    @Test
    void registeringIsIdempotentByCallId() {
        assertThat(store.saveIfAbsent(approval("c1", Instant.EPOCH))).isTrue();
        assertThat(store.saveIfAbsent(approval("c1", Instant.EPOCH))).isFalse();
        assertThat(store.find("c1")).isPresent();
    }

    @Test
    void openCardsOfOneRootComeOldestFirst() {
        store.saveIfAbsent(approval("newer", Instant.parse("2026-01-02T00:00:00Z")));
        store.saveIfAbsent(approval("older", Instant.parse("2026-01-01T00:00:00Z")));
        ToolApproval foreign = new ToolApproval("r", "c-foreign", "t", "{}",
                origin, UUID.randomUUID(), "task", Instant.EPOCH);
        store.saveIfAbsent(foreign);

        assertThat(store.openForRoot(root))
                .extracting(ToolApproval::callId)
                .containsExactly("older", "newer");
    }

    @Test
    void answeringDeletesExactlyTheOneQuestion() {
        store.saveIfAbsent(approval("c1", Instant.EPOCH));
        store.saveIfAbsent(approval("c2", Instant.EPOCH));
        store.delete("c1");
        assertThat(store.find("c1")).isEmpty();
        assertThat(store.find("c2")).isPresent();
    }

    @Test
    void cleanupByRootAndBySessionHitsBothAnchors() {
        store.saveIfAbsent(approval("c1", Instant.EPOCH));
        store.deleteForRoot(root);
        assertThat(store.openForRoot(root)).isEmpty();

        store.saveIfAbsent(approval("c2", Instant.EPOCH));
        store.deleteForSession(origin);   // matches the ORIGIN anchor
        assertThat(store.find("c2")).isEmpty();
    }
}
