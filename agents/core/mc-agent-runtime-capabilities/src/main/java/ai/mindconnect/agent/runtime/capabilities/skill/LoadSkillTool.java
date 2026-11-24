package ai.mindconnect.agent.runtime.capabilities.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Gives the agent a skill's instructions when it asks for them, instead of carrying all of them in every call. */
final class LoadSkillTool implements Tool {

    /** The name agents, prompts and stored bindings have always known the tool by. */
    static final String NAME = "skill";
    static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Loads a skill and returns its instructions. Call it before doing what the skill is for.",
            Map.of("type", "object",
                    "properties", Map.of("name", Map.of("type", "string", "description", "The skill's name")),
                    "required", List.of("name")));

    private final Supplier<List<SkillResolver>> resolvers;

    LoadSkillTool(Supplier<List<SkillResolver>> resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String name = String.valueOf(call.arguments().get("name"));
        return resolvers.get().stream()
                .map(resolver -> resolver.resolve(name, session))
                .flatMap(Optional::stream)
                .findFirst()
                .map(SkillDefinition::instructions)
                .map(ToolOutput::ok)
                .orElseGet(() -> ToolOutput.failed("Error: there is no skill named " + name));
    }
}
