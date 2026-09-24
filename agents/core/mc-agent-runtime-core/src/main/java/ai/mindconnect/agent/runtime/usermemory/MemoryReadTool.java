package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Reads one memory of the user by name — the system prompt lists only the
 * names and descriptions. Without a name it lists them all, for when the
 * prompt's list was cut short.
 */
public class MemoryReadTool implements Tool {

    public static final String NAME = "memory_read";

    private final UserMemoryService service;
    private final UserId userId;

    public MemoryReadTool(UserMemoryService service, UserId userId) {
        this.service = service;
        this.userId = userId;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return """
                Read a memory about the user in full, by the name the "Memory" section of your instructions
                lists it under. Read a memory before you rely on it or update it. Without a name, lists
                every memory with its description.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "The memory's name. Leave out to list all memories.")));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to read.";
        String name = MemoryWriteTool.string(arguments.get("name"));
        if (name == null || name.isBlank()) {
            List<MemoryEntry> entries = service.list(userId);
            if (entries.isEmpty()) return "No memories saved yet.";
            StringBuilder out = new StringBuilder(entries.size() + " memories:");
            entries.forEach(e -> out.append('\n').append(MemoryIndex.line(e)));
            return out.toString();
        }
        return service.read(userId, name)
                .map(e -> "# " + e.name() + " (" + e.type().wireName() + ")\n"
                        + e.description() + "\n\n" + e.content()
                        + "\n\n(last updated " + e.updatedAt() + ")")
                .orElse("No memory named '" + UserMemoryService.normaliseName(name)
                        + "'. Call memory_read without a name to list them.");
    }
}
