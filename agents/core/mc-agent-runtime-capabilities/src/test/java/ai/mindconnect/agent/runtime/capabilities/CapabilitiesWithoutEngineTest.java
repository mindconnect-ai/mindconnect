package ai.mindconnect.agent.runtime.capabilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.capabilities.permission.InMemoryApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.InMemoryToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolSearchPlugin;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import org.junit.jupiter.api.Test;

/**
 * Plays the engine's part by hand — runs the hook points the way an engine
 * would — to show that the capabilities need the API and nothing more.
 */
class CapabilitiesWithoutEngineTest {

    private static final Session SESSION = new Session(SessionId.of("s1"), UserId.of("ada"), null, null,
            Map.of(), Instant.EPOCH);
    private static final AgentDefinition RESEARCHER = new AgentDefinition("researcher", "Looks things up.",
            "You research.", ModelSettings.NONE, List.of(), 5, Map.of());
    private static final AgentDefinition LEAD = new AgentDefinition("lead", "Leads.", "You lead.",
            ModelSettings.NONE,
            List.of(ToolBinding.of("web_search"), ToolBinding.of("bash"), ToolBinding.deferred("read_pdf")),
            10, Map.of(SubAgentPlugin.SUB_AGENTS, List.of("researcher")));
    private static final AgentContext CONTEXT = new AgentContext(SESSION, LEAD, TurnId.of("t1"));

    private PluginRegistry registry;

    @BeforeEach
    void install_the_capabilities() {
        registry = new DefaultPluginRegistry();
        registry.contribute(AgentResolver.class, AgentResolver.of(List.of(LEAD, RESEARCHER)));
        registry.contribute(SkillResolver.class, skills(
                new SkillDefinition("citations", "How to cite sources.", "Cite like this.", List.of())));
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(tool("web_search"), tool("bash"), tool("read_pdf"))));
        registry.install(new SkillPlugin());
        registry.install(new ToolSearchPlugin(new InMemoryToolActivation(NamespaceProvider.fixed("test"))));
        registry.install(new SubAgentPlugin());
        registry.install(new PermissionPlugin(PermissionEffect.ALLOW, new InMemoryApprovalMemory(NamespaceProvider.fixed("test"))));
        registry.contribute(PermissionRuleSource.class, session -> List.of(
                new PermissionRule("bash", PermissionEffect.ASK)));
        registry.start();
    }

    @Test
    void the_instructions_list_the_skills() {
        String instructions = registry.run(RuntimeHook.INSTRUCTIONS, CONTEXT, context -> context.agent().instructions());

        assertThat(instructions).startsWith("You lead.").contains("citations: How to cite sources.");
    }

    @Test
    void the_offer_holds_back_deferred_tools_and_adds_what_the_capabilities_bring() {
        assertThat(offeredNames()).containsExactlyInAnyOrder(
                "web_search", "bash", "load_skill", "tool_search", "researcher");
    }

    @Test
    void a_denied_tool_is_not_offered() {
        registry.contribute(PermissionRuleSource.class, session -> List.of(
                new PermissionRule("web_search", PermissionEffect.DENY)));

        assertThat(offeredNames()).doesNotContain("web_search").contains("bash");
    }

    @Test
    void a_tool_that_asks_interrupts_and_runs_once_approved() {
        ToolCall call = new ToolCall("c1", "bash", Map.of(), CONTEXT.turnId(), SESSION.id());

        ToolOutput first = run(new ToolInvocation(CONTEXT, call, Optional.empty()));
        ToolOutput second = run(new ToolInvocation(CONTEXT, call, Optional.of(InterruptionAnswer.approve("i1"))));

        assertThat(first).isInstanceOfSatisfying(ToolOutput.Interrupted.class,
                interrupted -> assertThat(interrupted.kind()).isEqualTo(Interruption.TOOL_APPROVAL));
        assertThat(second).isEqualTo(ToolOutput.ok("ran bash"));
    }

    @Test
    void a_sub_agent_is_a_tool_that_resolves_by_the_agents_name() {
        ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));

        assertThat(tools.resolve("researcher", SESSION)).isPresent();
    }

    private List<String> offeredNames() {
        ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));
        return registry.run(RuntimeHook.TOOL_OFFER, CONTEXT, context -> tools.tools(context.session()).stream()
                        .filter(tool -> context.agent().uses(tool.name()))
                        .toList())
                .stream().map(ToolDefinition::name).toList();
    }

    private ToolOutput run(ToolInvocation invocation) {
        ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));
        return registry.run(RuntimeHook.TOOL_CALL, invocation, running ->
                tools.resolve(running.call().toolName(), SESSION).orElseThrow().execute(running.call(), SESSION));
    }

    private static SkillResolver skills(SkillDefinition... skills) {
        return new SkillResolver() {
            @Override
            public List<SkillSummary> skills(Session session) {
                return List.of(skills).stream()
                        .map(skill -> new SkillSummary(skill.name(), skill.description())).toList();
            }

            @Override
            public Optional<SkillDefinition> resolve(String skillName, Session session) {
                return List.of(skills).stream().filter(skill -> skill.name().equals(skillName)).findFirst();
            }
        };
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Does " + name + ".", Map.of("type", "object"));
            }

            @Override
            public ToolOutput execute(ToolCall call, Session session) {
                return ToolOutput.ok("ran " + name);
            }
        };
    }
}
