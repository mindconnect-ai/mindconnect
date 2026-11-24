package ai.mindconnect.agent.runtime.capabilities.toolresult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * Reads again a tool result that memory shows as a note. The session keeps
 * every message as it was said — the note is only what the model is shown
 * in its place — so the result is read from the session, by the id the note
 * names, and never from a copy.
 */
final class FetchToolResultTool implements Tool {

    private static final String ID = "id";

    static final ToolDefinition DEFINITION = new ToolDefinition(ToolResultPlugin.FETCH_TOOL,
            "Reads again the full content of a tool result that was removed from the context to save room. "
                    + "Use this when you see a note of the form `[Tool result removed to save context: … id=…]` "
                    + "and need the original output. Pass the id from the note.",
            Map.of("type", "object",
                    "properties", Map.of(ID, Map.of("type", "string",
                            "description", "The id of the removed tool result, taken verbatim from the note")),
                    "required", List.of(ID)));

    private final Supplier<SessionStore> sessions;

    FetchToolResultTool(Supplier<SessionStore> sessions) {
        this.sessions = sessions;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String id = call.arguments().get(ID) instanceof String given ? given.trim() : "";
        if (id.isEmpty()) {
            return ToolOutput.failed("Error: id is required");
        }
        // Only this session's messages are looked at: an id of another session finds nothing.
        return sessions.get().messages(session.id()).stream()
                .filter(message -> id.equals(message.id()))
                .findFirst()
                .map(message -> message.type() == TurnMessage.Type.TOOL_RESULT
                        ? ToolOutput.ok(message.text())
                        : ToolOutput.failed("Error: message " + id + " is not a tool result"))
                .orElseGet(() -> ToolOutput.failed("Error: no message with id " + id + " in this session"));
    }
}
