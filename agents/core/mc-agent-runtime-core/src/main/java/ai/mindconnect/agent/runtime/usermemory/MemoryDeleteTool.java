package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.List;
import java.util.Map;

/** Deletes a memory of the user — one that turned out wrong, or the user asked to forget. */
public class MemoryDeleteTool implements Tool {

    public static final String NAME = "memory_delete";

    private final UserMemoryService service;
    private final UserId userId;

    public MemoryDeleteTool(UserMemoryService service, UserId userId) {
        this.service = service;
        this.userId = userId;
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
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "The memory's name.")),
                "required", List.of("name"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to delete from.";
        String name = UserMemoryService.normaliseName(MemoryWriteTool.string(arguments.get("name")));
        return service.delete(userId, name)
                ? "Deleted memory '" + name + "'."
                : "No memory named '" + name + "'.";
    }
}
