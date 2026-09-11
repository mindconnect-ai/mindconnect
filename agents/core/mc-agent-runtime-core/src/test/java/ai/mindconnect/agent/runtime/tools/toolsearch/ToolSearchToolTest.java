package ai.mindconnect.agent.runtime.tools.toolsearch;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tool_search must find registry tools by name/description/group, activate
 * the matches for the session (so the next round offers them), respect the
 * groups override, and stay honest when nothing matches.
 */
class ToolSearchToolTest {

    private static Tool stubTool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parametersSchema() { return Map.of("type", "object"); }
            @Override public String execute(Map<String, Object> arguments) { return "ok"; }
        };
    }

    /** Registry with web_fetch, document_sections and code_execute in their groups. */
    private static ToolRegistry stubRegistry() {
        Map<String, Tool> tools = Map.of(
                "web_fetch", stubTool("web_fetch", "Fetches a URL over HTTP and returns the page content."),
                "document_sections", stubTool("document_sections", "Splits a Word or PDF document into sections."),
                "code_execute", stubTool("code_execute", "Executes a program in an isolated container."));
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        groups.put("web", new LinkedHashSet<>(List.of("web_fetch")));
        groups.put("documents", new LinkedHashSet<>(List.of("document_sections")));
        groups.put("code", new LinkedHashSet<>(List.of("code_execute")));
        return new ToolRegistry() {
            @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
                return Optional.ofNullable(tools.get(agentTool.name()));
            }
            @Override public Map<String, Set<String>> toolNamesByGroup() { return groups; }
        };
    }

    /** Minimal in-memory session store — activations persist on the session. */
    private static AgentSessionRepository sessionRepo(
            Map<SessionId, AgentSession> byId) {
        return new AgentSessionRepository() {
            @Override public AgentSession create(
                    AgentSession session) {
                byId.put(session.id(), session);
                return session;
            }
            @Override public Optional<AgentSession> update(SessionId id,
                    java.util.function.UnaryOperator<AgentSession> change) {
                return Optional.ofNullable(byId.computeIfPresent(id, (key, current) -> change.apply(current)));
            }
            @Override public Optional<AgentSession> findById(SessionId id) {
                return Optional.ofNullable(byId.get(id));
            }
            @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) {
                return List.of();
            }
            @Override public List<AgentSession> findByUser(
                    UserId userId) {
                return List.of();
            }
            @Override public List<AgentSession> findByParentSession(
                    SessionId parentSessionId) {
                return List.of();
            }
            @Override public void deleteById(SessionId id) { byId.remove(id); }
        };
    }

    private ToolRegistryRef ref;
    private DynamicToolActivations activations;
    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        ref = new ToolRegistryRef();
        ref.set(stubRegistry());
        sessionId = SessionId.random();
        Map<SessionId, AgentSession> sessions = new java.util.HashMap<>();
        sessions.put(sessionId, new AgentSession(
                sessionId, AgentId.random(), UserId.of("u"), ConversationId.random(),
                null, null, null, null, null, null, null));
        activations = new DynamicToolActivations(sessionRepo(sessions));
    }

    private ToolSearchTool tool(Set<String> assigned, Set<String> allowedGroups) {
        return new ToolSearchTool(ref, activations, sessionId,
                assigned, allowedGroups);
    }

    @Test
    void findsByDescriptionAndActivatesForTheSession() {
        String result = tool(Set.of(), Set.of("*")).execute(Map.of("query", "fetch a url over http"));

        assertThat(result).contains("web_fetch").contains("available to you from your next step");
        assertThat(activations.activated(sessionId)).contains("web_fetch");
    }

    @Test
    void ranksNameMatchesAboveDescriptionMatches() {
        String result = tool(Set.of(), Set.of("*")).execute(Map.of("query", "document", "max_results", 1));

        assertThat(result).contains("document_sections").doesNotContain("web_fetch");
        assertThat(activations.activated(sessionId)).containsExactly("document_sections");
    }

    @Test
    void groupsOverrideNarrowsTheSearchSpace() {
        // "execute" matches code_execute — but the agent may only search web tools.
        String result = tool(Set.of(), Set.of("web")).execute(Map.of("query", "execute program container"));

        assertThat(result).startsWith("No tools found");
        assertThat(activations.activated(sessionId)).isEmpty();
    }

    @Test
    void noMatchExplainsTheSearchSpace() {
        String result = tool(Set.of(), Set.of("*")).execute(Map.of("query", "quantum teleportation"));

        assertThat(result).startsWith("No tools found")
                .contains("web").contains("documents").contains("code");
    }

    @Test
    void assignedDeferredToolsAreSearchableWithoutAnyGroupGrant() {
        String result = tool(Set.of("code_execute"), Set.of())
                .execute(Map.of("query", "execute a program"));

        assertThat(result).contains("code_execute");
        assertThat(activations.activated(sessionId)).contains("code_execute");
        // ...but nothing outside the assigned set leaks in:
        assertThat(tool(Set.of("code_execute"), Set.of())
                .execute(Map.of("query", "fetch url http")))
                .startsWith("No tools found");
    }

    @Test
    void effectiveRefsHonourDeferredFlagAndInjectToolSearch() {
        AgentId agentId = AgentId.random();
        AgentTool always = AgentTool.of("web_fetch");
        AgentTool deferred = new AgentTool(AgentToolId.random(), "document_sections",
                null, Map.of("params", Map.of("path", "spec.docx")), true, true, false, null);
        AgentDefinition def = definition(agentId, List.of(always, deferred),
                new AgentDefinition.ToolSearchConfig(true, List.of("code")));

        // Before any search: deferred tool hidden, tool_search injected with its space.
        List<AgentTool> before = activations.effectiveRefs(def, sessionId);
        assertThat(before).extracting(AgentTool::name)
                .containsExactly("web_fetch", "tool_search");
        AgentTool search = before.get(1);
        assertThat(search.overrides().get("assigned")).isEqualTo(List.of("document_sections"));
        assertThat(search.overrides().get("groups")).isEqualTo(List.of("code"));

        // After activation: the CONFIGURED ref returns (pins intact) plus a
        // synthetic ref for the registry find.
        activations.activate(sessionId, List.of("document_sections", "code_execute"));
        List<AgentTool> after = activations.effectiveRefs(def, sessionId);
        assertThat(after).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("web_fetch", "document_sections", "code_execute", "tool_search");
        assertThat(after.stream().filter(t -> t.name().equals("document_sections")).findFirst()
                .orElseThrow().overrides()).containsKey("params");
    }

    @Test
    void effectiveRefsWithoutToolSearchBehaveLikeBefore() {
        AgentId agentId = AgentId.random();
        AgentDefinition def = definition(agentId,
                List.of(AgentTool.of("web_fetch")), null);

        assertThat(activations.effectiveRefs(def, sessionId))
                .extracting(AgentTool::name).containsExactly("web_fetch");
    }

    private static AgentDefinition definition(AgentId agentId, List<AgentTool> tools,
                                              AgentDefinition.ToolSearchConfig toolSearch) {
        return new AgentDefinition(agentId, "a", null, null, null, null, null,
                "cfg", 5, null, null, tools, List.of(), null, toolSearch, null, null);
    }

    @Test
    void factoryReadsGroupsOverrideAndRequiresServices() {
        var factory = new ToolSearchToolFactory();
        factory.bind(env(Map.of(ToolRegistryRef.class, ref, DynamicToolActivations.class, activations)));
        assertThat(factory.isAvailable()).isTrue();

        var agentTool = new AgentTool(AgentToolId.random(), "tool_search", null,
                Map.of("groups", List.of("Web", " documents ")), true, false, false, null);
        Tool created = factory.create(agentTool,
                new ToolCallScope(UserId.of("u"), sessionId, null));
        // May find web/documents tools but not code_execute.
        assertThat(created.execute(Map.of("query", "execute program container")))
                .doesNotContain("code_execute");

        var unbound = new ToolSearchToolFactory();
        unbound.bind(env(Map.of()));
        assertThat(unbound.isAvailable()).isFalse();
    }

    private static ToolEnvironment env(Map<Class<?>, Object> services) {
        return new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                return Optional.ofNullable((T) services.get(type));
            }
            @Override public Optional<String> getString(String key) { return Optional.empty(); }
        };
    }
}
