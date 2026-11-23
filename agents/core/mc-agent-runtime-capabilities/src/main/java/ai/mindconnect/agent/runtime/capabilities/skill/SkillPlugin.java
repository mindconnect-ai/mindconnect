package ai.mindconnect.agent.runtime.capabilities.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
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
                instructions + listing(summaries(resolvers, context.session())));
        // Offered without the agent naming it: an agent that has skills must be able to load them.
        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            if (summaries(resolvers, context.session()).isEmpty()) {
                return tools;
            }
            List<ToolDefinition> offered = new ArrayList<>(tools);
            offered.add(LoadSkillTool.DEFINITION);
            return offered;
        });
    }

    private static List<SkillSummary> summaries(Supplier<List<SkillResolver>> resolvers, Session session) {
        return resolvers.get().stream().flatMap(resolver -> resolver.skills(session).stream()).toList();
    }

    private static String listing(List<SkillSummary> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\n## Skills\n\nLoad a skill with "
                + LoadSkillTool.NAME + " before doing what it is for.\n");
        skills.forEach(skill -> text.append("- ").append(skill.name()).append(": ")
                .append(skill.description()).append('\n'));
        return text.toString();
    }
}
