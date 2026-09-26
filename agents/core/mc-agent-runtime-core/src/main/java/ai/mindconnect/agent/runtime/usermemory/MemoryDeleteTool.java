package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deletes a memory of the user — one that turned out wrong, or the user asked
 * to forget. What it reaches is the binding's {@link MemoryReach}.
 */
public class MemoryDeleteTool implements Tool {

    public static final String NAME = "memory_delete";

    private final UserMemoryService service;
    private final UserId userId;
    private final AgentId agentId;
    private final MemoryReach reach;

    public MemoryDeleteTool(UserMemoryService service, UserId userId) {
        this(service, userId, null, MemoryReach.USER);
    }

    public MemoryDeleteTool(UserMemoryService service, UserId userId, AgentId agentId, MemoryReach reach) {
        this.service = service;
        this.userId = userId;
        this.agentId = agentId;
        this.reach = reach == null ? MemoryReach.USER : reach;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return """
                Delete a memory about the user, by name: when it turned out wrong or outdated, or the user
                asks you to forget it. Forgetting is deleting: do not also write a note that it was forgotten.
                To correct a memory, write it again with memory_write instead.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "The memory's name."));
        MemoryTarget.addParameter(properties, reach,
                "Which memory the name is in; leave out to look in yours first, then the user's.");
        return Map.of("type", "object", "properties", properties, "required", List.of("name"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to delete from.";
        String name = UserMemoryService.normaliseName(MemoryWriteTool.string(arguments.get("name")));
        for (AgentId candidate : MemoryTarget.candidates(reach, agentId, arguments.get(MemoryTarget.ARG))) {
            if (service.delete(userId, candidate, name)) {
                return reach == MemoryReach.BOTH
                        ? "Deleted memory '" + name + "' from " + MemoryTarget.label(candidate) + "."
                        : "Deleted memory '" + name + "'.";
            }
        }
        return "No memory named '" + name + "'.";
    }
}
