package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.port.out.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Re-hydrates a previously evicted tool result by message id.
 * <p>
 * The auto-compact memory strategy may replace older TOOL_RESULT messages with a
 * short pointer stub of the form
 * <pre>[Tool result evicted from context — id=&lt;uuid&gt;, originally ~N tokens. ...]</pre>
 * If the agent later realises it needs the original content, it calls this tool with
 * the same id and gets back the full body.
 * <p>
 * The tool always reads from the original {@code Message.content} field, never the
 * compressed stub — eviction is purely a presentation-layer concern.
 */
public class FetchToolResultTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FetchToolResultTool.class);

    private final MessageRepository messageRepository;
    private final AgentSessionRepository sessionRepository;
    private final UUID sessionId;

    public FetchToolResultTool(MessageRepository messageRepository,
                               AgentSessionRepository sessionRepository,
                               UUID sessionId) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "fetch_tool_result";
    }

    @Override
    public String description() {
        return "Reloads the full content of a tool result that was evicted from the live context. " +
               "Use this when you see a stub of the form `[Tool result evicted from context — id=...]` " +
               "and need the original output. Pass the id from the stub.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of(
                                "type", "string",
                                "description", "The message id of the evicted tool result, taken verbatim from the eviction stub"
                        )
                ),
                "required", new String[]{"id"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String idStr = (String) arguments.get("id");
        if (idStr == null || idStr.isBlank()) return "Error: id is required";

        UUID messageId;
        try {
            messageId = UUID.fromString(idStr.trim());
        } catch (IllegalArgumentException e) {
            return "Error: id is not a valid UUID: " + idStr;
        }

        Optional<AgentSession> sess = sessionRepository.findById(sessionId);
        if (sess.isEmpty()) return "Error: session not found";
        UUID conversationId = sess.get().conversationId();

        Optional<Message> msg = messageRepository.findById(conversationId, messageId);
        if (msg.isEmpty()) {
            log.warn("fetch_tool_result: message {} not found in conversation {}", messageId, conversationId);
            return "Error: no message with id " + messageId + " in this session";
        }
        Message m = msg.get();
        if (m.type() != MessageType.TOOL_RESULT) {
            return "Error: message " + messageId + " is not a tool result (type=" + m.type() + ")";
        }
        return m.content();
    }
}
