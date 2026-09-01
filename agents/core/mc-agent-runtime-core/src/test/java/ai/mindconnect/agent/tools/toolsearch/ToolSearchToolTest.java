package ai.mindconnect.agent.tools.toolsearch;

import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tools.toolsearch.ToolSearchTool;
import ai.mindconnect.agent.tools.toolsearch.ToolSearchToolFactory;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
            @Override public Optional<Tool> resolve(AgentTool agentTool, Namespace namespace,
                                                    String userId, UUID sessionId) {
                return Optional.ofNullable(tools.get(agentTool.name()));
            }
            @Override public Map<String, Set<String>> toolNamesByGroup() { return groups; }
        };
    }

    /** Minimal in-memory session store — activations persist on the session. */
    private static ai.mindconnect.agent.port.out.AgentSessionRepository sessionRepo(
            Map<UUID, ai.mindconnect.agent.domain.AgentSession> byId) {
        return new ai.mindconnect.agent.port.out.AgentSessionRepository() {
            @Override public ai.mindconnect.agent.domain.AgentSession save(
                    ai.mindconnect.agent.domain.AgentSession session) {
                byId.put(session.id(), session);
                return session;
            }
            @Override public Optional<ai.mindconnect.agent.domain.AgentSession> findById(UUID id) {
                return Optional.ofNullable(byId.get(id));
            }
            @Override public List<ai.mindconnect.agent.domain.AgentSession> findByAgentDefinitionId(
                    UUID agentDefinitionId, Namespace namespace, String userId) {
                return List.of();
            }
            @Override public List<ai.mindconnect.agent.domain.AgentSession> findByUser(
                    Namespace namespace, String userId) {
                return List.of();
            }
            @Override public List<ai.mindconnect.agent.domain.AgentSession> findByParentSessionId(
                    UUID parentSessionId) {
                return List.of();
            }
            @Override public void deleteById(UUID id) { byId.remove(id); }
        };
    }

    private ToolRegistryRef ref;
    private DynamicToolActivations activations;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        ref = new ToolRegistryRef();
        ref.set(stubRegistry());
        sessionId = UUID.randomUUID();
        Map<UUID, ai.mindconnect.agent.domain.AgentSession> sessions = new java.util.HashMap<>();
        sessions.put(sessionId, new ai.mindconnect.agent.domain.AgentSession(
                sessionId, UUID.randomUUID(), new Namespace("test"), "u", UUID.randomUUID(),
                null, null, null, null, null, null, null));
        activations = new DynamicToolActivations(sessionRepo(sessions));
    }

    private ToolSearchTool tool(Set<String> assigned, Set<String> allowedGroups) {
        return new ToolSearchTool(ref, activations, new Namespace("test"), sessionId,
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
        UUID agentId = UUID.randomUUID();
        AgentTool always = AgentTool.of(agentId, "web_fetch");
        AgentTool deferred = new AgentTool(UUID.randomUUID(), agentId, "document_sections",
                null, Map.of("params", Map.of("path", "spec.docx")), true, true);
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
        UUID agentId = UUID.randomUUID();
        AgentDefinition def = definition(agentId,
                List.of(AgentTool.of(agentId, "web_fetch")), null);

        assertThat(activations.effectiveRefs(def, sessionId))
                .extracting(AgentTool::name).containsExactly("web_fetch");
    }

    private static AgentDefinition definition(UUID agentId, List<AgentTool> tools,
                                              AgentDefinition.ToolSearchConfig toolSearch) {
        return new AgentDefinition(agentId, new Namespace("test"), "a", null, null, null, null, null,
                "cfg", 5, null, null, tools, List.of(), null, toolSearch, null, null);
    }

    @Test
    void factoryReadsGroupsOverrideAndRequiresServices() {
        var factory = new ToolSearchToolFactory();
        factory.bind(env(Map.of(ToolRegistryRef.class, ref, DynamicToolActivations.class, activations)));
        assertThat(factory.isAvailable()).isTrue();

        var agentTool = new AgentTool(null, null, "tool_search", null,
                Map.of("groups", List.of("Web", " documents ")), true, false);
        Tool created = factory.create(agentTool,
                new ToolCallScope(new Namespace("test"), "u", sessionId, null));
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
