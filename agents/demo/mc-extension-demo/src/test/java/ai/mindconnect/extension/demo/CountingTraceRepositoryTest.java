package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.trace.domain.TraceId;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
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
        @Override public List<LlmCallTrace> findByTurn(String turn) { return List.of(); }
        @Override public List<LlmCallTrace> findBySession(SessionId session) { return List.of(); }
        @Override public List<LlmCallTrace> findDescendants(String root) { return List.of(); }
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
    void the_plugin_says_who_it_is() {
        assertThat(new DemoPlugin().name()).isEqualTo("demo-dungeon");
    }

    @Test
    void the_plugin_counts_on_the_trace_store_the_host_provided_before_it() {
        Recording store = new Recording();
        ai.mindconnect.plugin.PluginRegistry registry = new ai.mindconnect.plugin.DefaultPluginRegistry();
        registry.provide(LlmCallTraceRepository.class, () -> store);

        registry.install(new DemoPlugin());
        registry.get(LlmCallTraceRepository.class).save(null);

        assertThat(registry.get(LlmCallCounter.class).calls()).isEqualTo(1);
        assertThat(store.saved).hasSize(1);
    }
}
