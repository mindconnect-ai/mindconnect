package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves one memory about the user, or replaces the one of the same name.
 * The user is the one the chat belongs to and the agent the one calling —
 * never parameters, so an agent cannot write into someone else's memory.
 * Which memory — the user's own or this agent's — is the binding's
 * {@link MemoryReach}.
 */
public class MemoryWriteTool implements Tool {

    public static final String NAME = "memory_write";

    private final UserMemoryService service;
    private final UserId userId;
    private final AgentId agentId;
    private final SessionId sessionId;
    private final MemoryReach reach;

    public MemoryWriteTool(UserMemoryService service, UserId userId, SessionId sessionId) {
        this(service, userId, null, sessionId, MemoryReach.USER);
    }

    public MemoryWriteTool(UserMemoryService service, UserId userId, AgentId agentId, SessionId sessionId,
                           MemoryReach reach) {
        this.service = service;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.reach = reach == null ? MemoryReach.USER : reach;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        String which = switch (reach) {
            case USER -> "the user's persistent memory, which carries across chats and which every agent "
                    + "with the memory tools shares";
            case AGENT -> "your own persistent memory about the user, which carries across your chats with "
                    + "them and which no other agent sees";
            case BOTH -> "a persistent memory that carries across chats — 'scope' says which: 'user' for what "
                    + "every agent should know about the user, 'agent' for what only you need for your job";
        };
        return "Save something worth remembering about the user in " + which + ". "
                + """
                Writing under a name that exists replaces that memory — update an entry rather than adding a
                second one on the same topic.

                Save: who the user is (role, expertise, preferences), how they want the work done
                (corrections and approaches they confirmed — say why), ongoing projects, goals and
                decisions (with absolute dates, never "tomorrow"), and where to find things.
                Do not save: what only matters in this chat, what is in files or documents anyway,
                secrets such as passwords or keys, or anything the user asked you not to keep.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Short kebab-case key, e.g. 'preferred-language'. Reuse an existing name to "
                        + "update that memory."));
        properties.put("type", Map.of(
                "type", "string",
                "enum", List.of("user", "feedback", "project", "reference"),
                "description", "user: who they are; feedback: how they want things done; "
                        + "project: ongoing work and decisions; reference: where to find things."));
        properties.put("description", Map.of(
                "type", "string",
                "description", "One line (max " + UserMemoryService.MAX_DESCRIPTION
                        + " characters) saying what this memory is — it stands for it in every chat."));
        properties.put("content", Map.of(
                "type", "string",
                "description", "The memory in full, when there is more to it than the description. "
                        + "For feedback and project entries add a 'Why:' line and a 'How to apply:' line."));
        MemoryTarget.addParameter(properties, reach,
                "'user': the user's memory, shared by every agent; 'agent': your own, for your job only.");
        List<String> required = new ArrayList<>(List.of("name", "type", "description"));
        if (reach == MemoryReach.BOTH) required.add(MemoryTarget.ARG);
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (userId == null) return "No user in this chat — there is no memory to write to.";
        AgentId target = MemoryTarget.forWrite(reach, agentId, arguments.get(MemoryTarget.ARG));
        UserMemoryService.Written written = service.write(userId, target,
                string(arguments.get("name")),
                MemoryType.parse(string(arguments.get("type"))),
                string(arguments.get("description")),
                string(arguments.get("content")),
                sessionId);
        String verb = written.created() ? "Saved memory '" : "Updated memory '";
        return reach == MemoryReach.BOTH
                ? verb + written.entry().name() + "' in " + MemoryTarget.label(target) + "."
                : verb + written.entry().name() + "'.";
    }

    static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
