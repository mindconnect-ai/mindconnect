package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one memory of the user by name — the system prompt lists only the
 * names and descriptions. Without a name it lists them all, for when the
 * prompt's list was cut short. What it reaches is the binding's
 * {@link MemoryReach}.
 */
public class MemoryReadTool implements Tool {

    public static final String NAME = "memory_read";

    private final UserMemoryService service;
    private final UserId userId;
    private final AgentId agentId;
    private final MemoryReach reach;

    public MemoryReadTool(UserMemoryService service, UserId userId) {
        this(service, userId, null, MemoryReach.USER);
    }

    public MemoryReadTool(UserMemoryService service, UserId userId, AgentId agentId, MemoryReach reach) {
        this.service = service;
        this.userId = userId;
        this.agentId = agentId;
        this.reach = reach == null ? MemoryReach.USER : reach;
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
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "The memory's name. Leave out to list all memories."));
        MemoryTarget.addParameter(properties, reach,
                "Which memory the name is in; leave out to look in yours first, then the user's.");
        return Map.of("type", "object", "properties", properties);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to read.";
        String name = MemoryWriteTool.string(arguments.get("name"));
        if (name == null || name.isBlank()) {
            List<MemoryEntry> entries = service.list(userId, agentId, reach);
            if (entries.isEmpty()) return "No memories saved yet.";
            StringBuilder out = new StringBuilder(entries.size() + " memories:");
            entries.forEach(e -> out.append('\n').append(MemoryIndex.line(e, reach)));
            return out.toString();
        }
        String key = UserMemoryService.normaliseName(name);
        for (AgentId candidate : MemoryTarget.candidates(reach, agentId, arguments.get(MemoryTarget.ARG))) {
            Optional<MemoryEntry> found = service.read(userId, candidate, key);
            if (found.isPresent()) {
                MemoryEntry e = found.get();
                return "# " + e.name() + " (" + e.type().wireName()
                        + (reach == MemoryReach.BOTH ? ", " + MemoryTarget.label(e.agentId()) : "") + ")\n"
                        + e.description() + "\n\n" + e.content()
                        + "\n\n(last updated " + e.updatedAt() + ")";
            }
        }
        return "No memory named '" + key + "'. Call memory_read without a name to list them.";
    }
}
