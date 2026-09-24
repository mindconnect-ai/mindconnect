package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Saves one memory about the user, or replaces the one of the same name.
 * The user is the one the chat belongs to — never a parameter, so an agent
 * cannot write into someone else's memory.
 */
public class MemoryWriteTool implements Tool {

    public static final String NAME = "memory_write";

    private final UserMemoryService service;
    private final UserId userId;
    private final SessionId sessionId;

    public MemoryWriteTool(UserMemoryService service, UserId userId, SessionId sessionId) {
        this.service = service;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return """
                Save something worth remembering about the user in their persistent memory, which carries
                across chats. Writing under a name that exists replaces that memory — update an entry
                rather than adding a second one on the same topic.

                Save: who the user is (role, expertise, preferences), how they want the work done
                (corrections and approaches they confirmed — say why), ongoing projects, goals and
                decisions (with absolute dates, never "tomorrow"), and where to find things.
                Do not save: what only matters in this chat, what is in files or documents anyway,
                secrets such as passwords or keys, or anything the user asked you not to keep.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "Short kebab-case key, e.g. 'preferred-language'. Reuse an existing "
                                        + "name to update that memory."),
                        "type", Map.of(
                                "type", "string",
                                "enum", List.of("user", "feedback", "project", "reference"),
                                "description", "user: who they are; feedback: how they want things done; "
                                        + "project: ongoing work and decisions; reference: where to find things."),
                        "description", Map.of(
                                "type", "string",
                                "description", "One line (max " + UserMemoryService.MAX_DESCRIPTION
                                        + " characters) saying what this memory is — it stands for it in every chat."),
                        "content", Map.of(
                                "type", "string",
                                "description", "The memory in full, when there is more to it than the description. "
                                        + "For feedback and project entries add a 'Why:' line and a "
                                        + "'How to apply:' line.")),
                "required", List.of("name", "type", "description"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to write to.";
        UserMemoryService.Written written = service.write(userId,
                string(arguments.get("name")),
                MemoryType.parse(string(arguments.get("type"))),
                string(arguments.get("description")),
                string(arguments.get("content")),
                sessionId);
        return (written.created() ? "Saved memory '" : "Updated memory '") + written.entry().name() + "'.";
    }

    static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
