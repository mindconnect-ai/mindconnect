package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.common.Versions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An edit form's save is checked against the version the form was opened with; a
 * change without a version is re-applied when another save came in between.
 */
class AgentRegistryServiceVersionTest {

    private final Repo repo = new Repo();
    private final AgentRegistryService service = new AgentRegistryService(repo);

    @Test
    void aFormOpenedBeforeAnotherSaveIsRefused() {
        AgentDefinition agent = repo.save(AgentDefinition.create("scout", "d", "p", "hi", "cfg"));
        service.update(agent.id(), AgentPatch.of().withDescription("first"), 1L);

        assertThatThrownBy(() -> service.update(agent.id(), AgentPatch.of().withDescription("second"), 1L))
                .isInstanceOf(StaleVersionException.class);
        assertThat(repo.findById(agent.id())).map(AgentDefinition::description).contains("first");
    }

    @Test
    void aChangeWithoutVersionIsAppliedAgainAfterARacingSave() {
        AgentDefinition agent = repo.save(AgentDefinition.create("scout", "d", "p", "hi", "cfg"));
        repo.raceOnce.set(1);

        AgentDefinition updated = service.updateTools(agent.id(), tools -> {
            List<AgentTool> next = new ArrayList<>(tools);
            next.add(AgentTool.of("web_search", "Searches the web."));
            return next;
        });

        assertThat(updated.tools()).extracting(AgentTool::name).contains("web_search", "raced");
        assertThat(repo.saves.get()).as("the first attempt lost the race and ran again").isEqualTo(4);
    }

    /** Versioned like the real stores; {@link #raceOnce} lets another save slip in before the next one. */
    private static final class Repo implements AgentDefinitionRepository {
        final Map<AgentId, AgentDefinition> byId = new ConcurrentHashMap<>();
        final AtomicInteger raceOnce = new AtomicInteger();
        final AtomicInteger saves = new AtomicInteger();

        @Override
        public AgentDefinition save(AgentDefinition def) {
            saves.incrementAndGet();
            if (raceOnce.getAndSet(0) == 1) {
                AgentDefinition current = byId.get(def.id());
                List<AgentTool> tools = new ArrayList<>(current.tools());
                tools.add(AgentTool.of("raced", "Added by another save."));
                save(current.withTools(tools));
            }
            return byId.compute(def.id(), (id, current) -> def.withVersion(Versions.next(
                    current == null ? null : current.version(), def.version(), "AgentDefinition", id.value())));
        }

        @Override public Optional<AgentDefinition> findById(AgentId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<AgentDefinition> findAll() { return List.copyOf(byId.values()); }
        @Override public Optional<AgentDefinition> findByName(String name) {
            return byId.values().stream().filter(d -> d.name().equals(name)).findFirst();
        }
        @Override public void deleteById(AgentId id) { byId.remove(id); }
    }
}
