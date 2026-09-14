package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.session.InlineSessionAgent;
import ai.mindconnect.agent.runtime.domain.session.SessionAgentRef;
import ai.mindconnect.agent.runtime.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which agent a session actually runs — the question every turn starts with,
 * in its three shapes: a legacy reference, a registry agent with this chat's
 * overrides, and an agent that exists only inside the session.
 */
class SessionAgentResolverTest {

    private final Map<AgentId, AgentDefinition> registry = new ConcurrentHashMap<>();
    private final SessionAgentResolver resolver = new SessionAgentResolver(new Repo());

    private class Repo implements AgentDefinitionRepository {
        @Override public AgentDefinition save(AgentDefinition d) { registry.put(d.id(), d); return d; }
        @Override public Optional<AgentDefinition> findById(AgentId id) { return Optional.ofNullable(registry.get(id)); }
        @Override public List<AgentDefinition> findAll() {
            return registry.values().stream().toList();
        }
        @Override public Optional<AgentDefinition> findByName(String name) {
            return registry.values().stream()
                    .filter(d -> d.name().equals(name)).findFirst();
        }
        @Override public void deleteById(AgentId id) { registry.remove(id); }
    }

    private AgentDefinition registered(String name, String llm) {
        var def = AgentDefinition.create(name, "d", "the agent's own prompt", "hi", llm);
        registry.put(def.id(), def);
        return def;
    }

    private static AgentSession sessionFor(AgentId agentId) {
        return AgentSession.start(agentId, UserId.of("alice"), ConversationId.random());
    }

    @Test
    void aSessionWithoutSessionAgentsResolvesThroughTheRegistry() {
        var def = registered("Poet", "claude");
        assertThat(resolver.resolve(sessionFor(def.id()))).isEqualTo(def);
    }

    @Test
    void anUnknownAgentIsReportedAsNotFound() {
        assertThatThrownBy(() -> resolver.resolve(sessionFor(AgentId.random())))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void anInlineAgentIsAssembledFromTheSessionItself() {
        var inline = InlineSessionAgent.of("Chat", "be helpful", "gpt",
                List.of("file_read"), List.of());
        var session = sessionFor(inline.id()).withSessionAgents(List.of(inline));

        AgentDefinition resolved = resolver.resolve(session);

        assertThat(registry).isEmpty();                       // nothing was registered
        assertThat(resolved.id()).isEqualTo(inline.id());     // and the id carries through
        assertThat(resolved.name()).isEqualTo("Chat");
        assertThat(resolved.systemPrompt()).isEqualTo("be helpful");
        assertThat(resolved.llmConfigName()).isEqualTo("gpt");
        assertThat(resolved.tools()).extracting(t -> t.name()).containsExactly("file_read");
        // Not a knob a chat gets to turn: it decides how long conversations
        // survive compression.
        assertThat(resolved.effectiveMemoryConfig()).isEqualTo(SummarizingWindowConfig.DEFAULT);
        assertThat(resolved.effectiveCallableAgents()).as("no roster, nobody to call").isEmpty();
        assertThat(resolved.mayCall("anyone")).isFalse();
        assertThat(resolved.delegates()).isFalse();
    }

    @Test
    void anInlineAgentsRosterBecomesTheDefinitionsRoster() {
        var plain = InlineSessionAgent.of("helper", "help", "gpt", List.of("run_agent"), List.of());
        var inline = new InlineSessionAgent(plain.id(), true, plain.label(), plain.systemPrompt(),
                plain.llmConfigName(), plain.tools(), List.of("explorer"));
        var session = sessionFor(inline.id()).withSessionAgents(List.of(inline));

        AgentDefinition resolved = resolver.resolve(session);

        assertThat(resolved.effectiveCallableAgents()).containsExactly("explorer");
        assertThat(resolved.mayCall("deployer")).isFalse();
    }

    @Test
    void aRefWithoutOverridesIsTheAgentAsConfigured() {
        var def = registered("Poet", "claude");
        var session = sessionFor(def.id())
                .withSessionAgents(List.of(new SessionAgentRef(def.id(), true, "Poet", null, null)));

        assertThat(resolver.resolve(session)).isEqualTo(def);
    }

    @Test
    void aRefTakesThisChatsModelAndTools() {
        var def = registered("Poet", "claude");
        var ownTools = InlineSessionAgent.of("x", "p", "gpt", List.of("todo_read"), List.of()).tools();
        var session = sessionFor(def.id()).withSessionAgents(List.of(
                new SessionAgentRef(def.id(), true, "Poet", "gpt-4o", ownTools)));

        AgentDefinition resolved = resolver.resolve(session);

        assertThat(resolved.llmConfigName()).isEqualTo("gpt-4o");
        assertThat(resolved.tools()).extracting(t -> t.name()).containsExactly("todo_read");
        assertThat(resolved.systemPrompt()).isEqualTo("the agent's own prompt");
        assertThat(resolved.name()).isEqualTo("Poet");
    }

    private AgentDefinition registeredWithRoster(String name, List<String> roster) {
        var def = AgentDefinition.create(name, "d", "p", "hi", "claude").withCallableAgents(roster);
        registry.put(def.id(), def);
        return def;
    }

    private static AgentSession withRoster(AgentDefinition def, List<String> roster) {
        return sessionFor(def.id()).withSessionAgents(List.of(
                new SessionAgentRef(def.id(), true, def.name(), null, null, null, roster)));
    }

    /** The agent is the template: a chat's roster replaces it, and may name agents it never had. */
    @Test
    void aRefsRosterReplacesTheAgents() {
        var def = registeredWithRoster("Planner", List.of("explorer", "verifier"));

        AgentDefinition resolved = resolver.resolve(withRoster(def, List.of("verifier", "deployer")));

        assertThat(resolved.effectiveCallableAgents()).containsExactly("verifier", "deployer");
        assertThat(resolved.mayCall("explorer")).isFalse();
        assertThat(resolver.resolve(withRoster(def, List.of())).delegates())
                .as("an empty choice is nobody").isFalse();
    }

    @Test
    void aRefWithoutARosterKeepsTheAgents() {
        var def = registeredWithRoster("Planner", List.of("explorer"));

        assertThat(resolver.resolve(withRoster(def, null)).effectiveCallableAgents())
                .containsExactly("explorer");
    }

    @Test
    void aRefToAnAgentThatWasDeletedFailsLoudly() {
        AgentId gone = AgentId.random();
        var session = sessionFor(gone)
                .withSessionAgents(List.of(new SessionAgentRef(gone, true, "Gone", null, null, null)));

        assertThatThrownBy(() -> resolver.resolve(session)).isInstanceOf(DomainException.class);
    }
}
