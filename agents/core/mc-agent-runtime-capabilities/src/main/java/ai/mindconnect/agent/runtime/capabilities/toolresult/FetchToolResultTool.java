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
 * Reads a tool result of the session, a page at a time. The session keeps
 * every result as the tool returned it — the page or the note is only what
 * the model is shown in its place — so the result is read from the session,
 * by the id the page or the note names, and never from a copy.
 */
final class FetchToolResultTool implements Tool {

    private static final String ID = "id";
    private static final String OFFSET = "offset";
    private static final String LIMIT = "limit";

    static final ToolDefinition DEFINITION = new ToolDefinition(ToolResultPlugin.FETCH_TOOL,
            "Reads a tool result of this conversation that you were not shown in full: one that was cut off with "
                    + "a line `[showing characters … Read on with fetch_tool_result: id=…, offset=…]`, or one "
                    + "that was removed with a note `[Tool result removed to save context: … id=…]`. Pass the id "
                    + "from that line and, to read on, the offset it names. The answer is one page; when there "
                    + "is more, its last line names the next offset.",
            Map.of("type", "object",
                    "properties", Map.of(
                            ID, Map.of("type", "string",
                                    "description", "The id of the tool result, taken verbatim from the line or note"),
                            OFFSET, Map.of("type", "integer",
                                    "description", "The character to start at. Default 0, the beginning"),
                            LIMIT, Map.of("type", "integer",
                                    "description", "How many characters to read, at most one page. Default one page")),
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
                        ? page(message, call, session)
                        : ToolOutput.failed("Error: message " + id + " is not a tool result"))
                .orElseGet(() -> ToolOutput.failed("Error: no message with id " + id + " in this session"));
    }

    private static ToolOutput page(TurnMessage result, ToolCall call, Session session) {
        String text = result.text();
        // One page of the tool that returned it: reading on must not fill the context the page kept free.
        int page = session.agent() == null ? ToolResultPlugin.DEFAULT_PAGE_CHARS
                : ToolResultPlugin.pageChars(session.agent(), result.metadata().get("toolName"));
        int offset = call.arguments().get(OFFSET) instanceof Number from ? Math.max(0, from.intValue()) : 0;
        int limit = call.arguments().get(LIMIT) instanceof Number wanted && wanted.intValue() > 0
                ? Math.min(wanted.intValue(), page) : page;
        if (offset >= text.length() && !text.isEmpty()) {
            return ToolOutput.failed("Error: offset " + offset + " is past the end of the result ("
                    + text.length() + " characters)");
        }
        int end = Math.min(text.length(), offset + limit);
        String shown = text.substring(offset, end);
        return ToolOutput.ok(end < text.length()
                ? shown + "\n… [characters " + offset + "-" + end + " of " + text.length()
                        + ". Read on with offset=" + end + "]"
                : shown);
    }
}
