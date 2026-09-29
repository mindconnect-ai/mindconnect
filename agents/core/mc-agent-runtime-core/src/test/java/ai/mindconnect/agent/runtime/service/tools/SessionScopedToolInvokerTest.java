package ai.mindconnect.agent.runtime.service.tools;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.message.domain.ConversationId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host side of "a tool that calls tools": a caller hands over a name and
 * arguments, and the runtime runs that tool in the SESSION's scope. The
 * sandbox that asked never says who the user is — these tests pin that the
 * scope comes from the session, and that the two things a program must not
 * reach stay unreachable.
 */
class SessionScopedToolInvokerTest {

    private static final String CALLER = "code_execute";

    private final Map<SessionId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<AgentId, AgentDefinition> definitions = new ConcurrentHashMap<>();
    /** Every scope a resolved tool was asked for, in order. */
    private final List<ToolCallScope> seenScopes = new ArrayList<>();

    @Test
    void aCallerRunsOneOfItsAgentsTools() {
        AgentSession session = session(tool("calendar_list"), tool(CALLER));

        ScopedToolInvoker.Result result = invoker().invoke(
                scope(session), CALLER, "calendar_list", Map.of("from", "monday"));

        assertThat(result.failed()).isFalse();
        assertThat(result.output()).isEqualTo("calendar_list ran with {from=monday}");
    }

    @Test
    void theToolSeesTheSessionsScope_notTheCallersWord() {
        AgentSession session = session(tool("calendar_list"), tool(CALLER));

        // A scope as a sandboxed caller might hand it over: no user, no directory.
        invoker().invoke(new ToolCallScope(null, session.id(), null), CALLER, "calendar_list", Map.of());

        assertThat(seenScopes).hasSize(1);
        ToolCallScope used = seenScopes.get(0);
        assertThat(used.userId()).isEqualTo(UserId.of("alice"));
        assertThat(used.workingDir()).isEqualTo("/home/alice/project");
        assertThat(used.sessionId()).isEqualTo(session.id());
    }

    @Test
    void aCallerCannotCallItself() {
        AgentSession session = session(tool(CALLER));

        ScopedToolInvoker.Result result = invoker().invoke(scope(session), CALLER, CALLER, Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("cannot be called from 'code_execute'");
        assertThat(seenScopes).isEmpty();
    }

    @Test
    void delegationStaysOutOfReach() {
        AgentSession session = session(tool(InlineAgentTools.RUN_AGENT), tool(CALLER));

        ScopedToolInvoker.Result result = invoker().invoke(
                scope(session), CALLER, InlineAgentTools.RUN_AGENT, Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("cannot be called from");
    }

    @Test
    void anUnknownToolComesBackWithTheListThatWouldHaveWorked() {
        AgentSession session = session(tool("calendar_list"), tool(CALLER));

        ScopedToolInvoker.Result result = invoker().invoke(scope(session), CALLER, "send_missiles", Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("unknown tool 'send_missiles'").contains("calendar_list");
    }

    @Test
    void aToolThatReturnsAnErrorTextCountsAsFailed() {
        AgentSession session = session(tool("failing"), tool(CALLER));

        ScopedToolInvoker.Result result = invoker().invoke(scope(session), CALLER, "failing", Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).startsWith("Error:");
    }

    @Test
    void withoutASessionThereIsNothingToCall() {
        ScopedToolInvoker.Result result = invoker().invoke(
                ToolCallScope.detached(UserId.of("alice")), CALLER, "calendar_list", Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("needs a session");
    }

    @Test
    void theCallableToolsAreTheAgentsOwn_withoutTheCallerAndWithoutWhatIsSwitchedOff() {
        AgentSession session = session(tool("calendar_list"), tool("gmail_send"),
                disabled("archived_tool"), tool(InlineAgentTools.RUN_AGENTS), tool(CALLER));

        List<String> callable = invoker().callableTools(scope(session), CALLER);

        assertThat(callable).containsExactly("calendar_list", "gmail_send");
    }

    @Test
    void withoutASessionNothingIsCallable() {
        assertThat(invoker().callableTools(ToolCallScope.detached(UserId.of("alice")), CALLER)).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private SessionScopedToolInvoker invoker() {
        AgentSessionRepository repo = new MapSessions(sessions);
        AgentSessionService service = new AgentSessionService(
                new MapDefinitions(definitions), repo, null, null, null, null, null, null);
        return new SessionScopedToolInvoker(this::registry, service, new MapDefinitions(definitions),
                new DynamicToolActivations(repo), new ToolExecutor(), SubAgentSupport.disabled());
    }

    /** Resolves every requested tool into one that echoes its name and arguments. */
    private ToolRegistry registry() {
        return (agentTool, scope) -> {
            seenScopes.add(scope);
            return Optional.of(new EchoTool(agentTool.name()));
        };
    }

    private AgentSession session(AgentTool... tools) {
        AgentDefinition def = AgentDefinition.create("main", "d", "p", null, "gpt")
                .withTools(List.of(tools));
        definitions.put(def.id(), def);
        AgentSession session = AgentSession.start(def.id(), UserId.of("alice"), ConversationId.random())
                .withWorkingDir("/home/alice/project");
        sessions.put(session.id(), session);
        return session;
    }

    /** A scope the way a tool task builds it, to be handed on by the caller. */
    private static ToolCallScope scope(AgentSession session) {
        return new ToolCallScope(session.userId(), session.id(), null);
    }

    private static AgentTool tool(String name) {
        return new AgentTool(AgentToolId.random(), name, null, Map.of());
    }

    private static AgentTool disabled(String name) {
        return new AgentTool(AgentToolId.random(), name, null, Map.of(), false, false, false, null);
    }

    private record EchoTool(String name) implements Tool {
        @Override public String description() { return "echo"; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }
        @Override public String execute(Map<String, Object> arguments) {
            return "failing".equals(name)
                    ? "Error: this tool always says no"
                    : name + " ran with " + arguments;
        }
    }

    private record MapDefinitions(Map<AgentId, AgentDefinition> byId) implements AgentDefinitionRepository {
        @Override public AgentDefinition save(AgentDefinition d) { byId.put(d.id(), d); return d; }
        @Override public Optional<AgentDefinition> findById(AgentId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<AgentDefinition> findByName(String name) {
            return byId.values().stream().filter(d -> d.name().equals(name)).findFirst();
        }
        @Override public List<AgentDefinition> findAll() { return List.copyOf(byId.values()); }
        @Override public void deleteById(AgentId id) { byId.remove(id); }
    }

    private record MapSessions(Map<SessionId, AgentSession> byId) implements AgentSessionRepository {
        @Override public AgentSession create(AgentSession s) { byId.put(s.id(), s); return s; }
        @Override public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
            return Optional.ofNullable(byId.computeIfPresent(id, (key, current) -> change.apply(current)));
        }
        @Override public Optional<AgentSession> findById(SessionId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) { return List.of(); }
        @Override public List<AgentSession> findByUser(UserId user) { return List.of(); }
        @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
        @Override public void deleteById(SessionId id) { byId.remove(id); }
    }
}
