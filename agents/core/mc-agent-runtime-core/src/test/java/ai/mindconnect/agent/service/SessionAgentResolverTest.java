package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.session.InlineSessionAgent;
import ai.mindconnect.agent.domain.session.SessionAgentRef;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which agent a session actually runs — the question every turn starts with,
 * in its three shapes: a legacy reference, a registry agent with this chat's
 * overrides, and an agent that exists only inside the session.
 */
class SessionAgentResolverTest {

    private static final Namespace NS = new Namespace("local");

    private final Map<UUID, AgentDefinition> registry = new ConcurrentHashMap<>();
    private final SessionAgentResolver resolver = new SessionAgentResolver(new Repo());

    private class Repo implements AgentDefinitionRepository {
        @Override public AgentDefinition save(AgentDefinition d) { registry.put(d.id(), d); return d; }
        @Override public Optional<AgentDefinition> findById(UUID id) { return Optional.ofNullable(registry.get(id)); }
        @Override public List<AgentDefinition> findByNamespace(Namespace ns) { return List.copyOf(registry.values()); }
        @Override public Optional<AgentDefinition> findByName(Namespace ns, String name) {
            return registry.values().stream().filter(d -> d.name().equals(name)).findFirst();
        }
        @Override public void deleteById(UUID id) { registry.remove(id); }
    }

    private AgentDefinition registered(String name, String llm) {
        var def = AgentDefinition.create(NS, name, "d", "the agent's own prompt", "hi", llm);
        registry.put(def.id(), def);
        return def;
    }

    private static AgentSession sessionFor(UUID agentId) {
        return AgentSession.start(agentId, NS, "alice", UUID.randomUUID());
    }

    @Test
    void aSessionWithoutSessionAgentsResolvesThroughTheRegistry() {
        var def = registered("Poet", "claude");
        assertThat(resolver.resolve(sessionFor(def.id()))).isEqualTo(def);
    }

    @Test
    void anUnknownAgentIsReportedAsNotFound() {
        assertThatThrownBy(() -> resolver.resolve(sessionFor(UUID.randomUUID())))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void anInlineAgentIsAssembledFromTheSessionItself() {
        var inline = InlineSessionAgent.of("Chat", "be helpful", "gpt",
                List.of("workspace_read"), true);
        var session = sessionFor(inline.id()).withSessionAgents(List.of(inline));

        AgentDefinition resolved = resolver.resolve(session);

        assertThat(registry).isEmpty();                       // nothing was registered
        assertThat(resolved.id()).isEqualTo(inline.id());     // and the id carries through
        assertThat(resolved.name()).isEqualTo("Chat");
        assertThat(resolved.systemPrompt()).isEqualTo("be helpful");
        assertThat(resolved.llmConfigName()).isEqualTo("gpt");
        assertThat(resolved.tools()).extracting(t -> t.name()).containsExactly("workspace_read");
        assertThat(resolved.toolSearchOrOff().enabled()).isTrue();
        // Not a knob a chat gets to turn: it decides how long conversations
        // survive compression.
        assertThat(resolved.effectiveMemoryConfig()).isEqualTo(SummarizingWindowConfig.DEFAULT);
    }

    @Test
    void aRefWithoutOverridesIsTheAgentAsConfigured() {
        var def = registered("Poet", "claude");
        var session = sessionFor(def.id())
                .withSessionAgents(List.of(new SessionAgentRef(def.id(), true, "Poet", null, null, null)));

        assertThat(resolver.resolve(session)).isEqualTo(def);
    }

    @Test
    void aRefTakesThisChatsModelAndToolsButNeverItsPrompt() {
        var def = registered("Poet", "claude");
        var ownTools = InlineSessionAgent.of("x", "p", "gpt", List.of("todo_read"), false).tools();
        var session = sessionFor(def.id()).withSessionAgents(List.of(
                new SessionAgentRef(def.id(), true, "Poet", "gpt-4o", ownTools,
                        new AgentDefinition.ToolSearchConfig(true, List.of("web")))));

        AgentDefinition resolved = resolver.resolve(session);

        assertThat(resolved.llmConfigName()).isEqualTo("gpt-4o");
        assertThat(resolved.tools()).extracting(t -> t.name()).containsExactly("todo_read");
        assertThat(resolved.toolSearchOrOff().groups()).containsExactly("web");
        // The prompt stays the agent's: a ref wearing a foreign prompt would
        // make "sessions of agent X" meaningless.
        assertThat(resolved.systemPrompt()).isEqualTo("the agent's own prompt");
        assertThat(resolved.name()).isEqualTo("Poet");
    }

    @Test
    void aRefToAnAgentThatWasDeletedFailsLoudly() {
        UUID gone = UUID.randomUUID();
        var session = sessionFor(gone)
                .withSessionAgents(List.of(new SessionAgentRef(gone, true, "Gone", null, null, null)));

        assertThatThrownBy(() -> resolver.resolve(session)).isInstanceOf(DomainException.class);
    }
}
