package ai.mindconnect.agent.runtime.capabilities.skill;

import java.util.ArrayList;
import java.util.Optional;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Skills: the agent reads a list of what there is in its instructions and
 * loads one when it needs it. Where skills come from is a
 * {@link SkillResolver} someone else contributes.
 */
public final class SkillPlugin implements Plugin {

    /**
     * The key in an agent's metadata that picks its skills: a list of skill
     * names. Without the key the agent may load every skill there is — one
     * added later is in reach without touching the agent; with an empty
     * list it has none, and is offered neither the list nor the tool.
     */
    public static final String SKILLS = "skills";

    /** The name of the tool that loads a skill, for whoever treats what it returns unlike other tool results. */
    public static final String TOOL = LoadSkillTool.NAME;

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public void install(PluginRegistry registry) {
        Supplier<List<SkillResolver>> resolvers = () -> registry.all(SkillResolver.class);
        LoadSkillTool loadSkill = new LoadSkillTool(resolvers);
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(loadSkill)));

        registry.after(RuntimeHook.INSTRUCTIONS, (context, instructions) ->
                instructions + listing(summaries(resolvers, context)));
        // Offered without the agent naming it: an agent that has skills must be able to load them.
        // The tool is not told which agent calls it; a skill the agent did not pick is refused here.
        registry.before(RuntimeHook.TOOL_CALL, invocation ->
                LoadSkillTool.NAME.equals(invocation.call().toolName())
                        && !mayLoad(invocation.context(), String.valueOf(invocation.call().arguments().get("name")))
                        ? BeforeOutcome.stop(ToolOutput.failed("Error: there is no skill named "
                                + invocation.call().arguments().get("name")))
                        : BeforeOutcome.proceed(invocation));
        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            if (summaries(resolvers, context).isEmpty()) {
                return tools;
            }
            List<ToolDefinition> offered = new ArrayList<>(tools);
            offered.add(LoadSkillTool.DEFINITION);
            return offered;
        });
    }

    /** The skills there are for the session that this agent may load. */
    private static List<SkillSummary> summaries(Supplier<List<SkillResolver>> resolvers, AgentContext context) {
        return resolvers.get().stream()
                .flatMap(resolver -> resolver.skills(context.session()).stream())
                .filter(skill -> mayLoad(context, skill.name()))
                .toList();
    }

    /** The skills an agent may load; empty when it may load every skill, an empty list when none. */
    public static Optional<List<String>> skillsOf(AgentDefinition agent) {
        return agent.metadata().get(SKILLS) instanceof List<?> picked
                ? Optional.of(picked.stream().map(String::valueOf).toList()) : Optional.empty();
    }

    /** The agent with every skill in reach, also the ones added later: no entry at all. */
    public static AgentDefinition withEverySkill(AgentDefinition agent) {
        return agent.with(SKILLS, null);
    }

    /** The agent with only these skills in reach; an empty list is none. */
    public static AgentDefinition withSkills(AgentDefinition agent, List<String> names) {
        return agent.with(SKILLS, List.copyOf(names));
    }

    private static boolean mayLoad(AgentContext context, String skillName) {
        return skillsOf(context.agent()).map(picked -> picked.contains(skillName)).orElse(true);
    }

    private static String listing(List<SkillSummary> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\n## Skills\n\nLoad a skill with the `"
                + LoadSkillTool.NAME + "` tool before doing what it is for.\n");
        skills.forEach(skill -> text.append("- ").append(skill.name()).append(": ")
                .append(skill.description()).append('\n'));
        return text.toString();
    }
}
