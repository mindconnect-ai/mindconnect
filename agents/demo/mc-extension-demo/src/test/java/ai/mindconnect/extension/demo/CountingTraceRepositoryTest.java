package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CountingTraceRepositoryTest {

    /** A store that only remembers what it was asked to save. */
    static final class Recording implements LlmCallTraceRepository {
        final List<LlmCallTrace> saved = new ArrayList<>();
        int deletes;
        @Override public void save(LlmCallTrace trace) { saved.add(trace); }
        @Override public List<LlmCallTrace> findByTurn(ChatTurnId turn) { return List.of(); }
        @Override public List<LlmCallTrace> findBySession(SessionId session) { return List.of(); }
        @Override public List<LlmCallTrace> findByConversation(ConversationId conversation) { return List.of(); }
        @Override public List<LlmCallTrace> findDescendants(ChatTurnId root) { return List.of(); }
        @Override public Optional<LlmCallTrace> findById(TraceId id) { return Optional.empty(); }
        @Override public void deleteBySession(SessionId session) { deletes++; }
    }

    @Test
    void counts_every_save_and_passes_everything_through() {
        Recording store = new Recording();
        LlmCallCounter counter = new LlmCallCounter();
        CountingTraceRepository counting = new CountingTraceRepository(store, counter);

        counting.save(null);
        counting.save(null);
        counting.deleteBySession(SessionId.random());

        assertThat(counter.calls()).isEqualTo(2);
        assertThat(store.saved).hasSize(2);
        assertThat(store.deletes).isEqualTo(1);
        assertThat(counting.findById(null)).isEmpty();
    }

    @Test
    void the_feature_says_who_it_is() {
        DemoFeature feature = new DemoFeature();

        assertThat(feature.name()).isEqualTo("demo-dungeon");
        assertThat(feature.dependsOn()).isEmpty();
    }
}
