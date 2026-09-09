package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.responses.wire.ResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translation between the protocol's vocabulary and OpenAI's wire format.
 *
 * <p>Little is invented here. The protocol was modelled on the Responses API
 * to begin with — {@code Response} even carries an {@code output_text} for
 * the same reason OpenAI does — so most of this is renaming, and the places
 * that are not are commented.
 */
public final class ResponsesMapper {

    private final ObjectMapper json;

    public ResponsesMapper(ObjectMapper json) {
        this.json = json;
    }

    // ── Request ─────────────────────────────────────────────────────────────

    /**
     * The {@code input} field as protocol items. A client may send a bare
     * string, or the array form; both mean the same thing for the common
     * case and OpenAI's own SDKs use each.
     */
    public List<ConversationItem> toItems(JsonNode input) {
        if (input == null || input.isNull()) {
            return List.of();
        }
        if (input.isTextual()) {
            return List.of(ConversationItem.Message.user(input.asText()));
        }
        if (!input.isArray()) {
            throw new IllegalArgumentException("'input' must be a string or an array of items");
        }
        List<ConversationItem> items = new ArrayList<>();
        for (JsonNode node : input) {
            ConversationItem item = toItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private ConversationItem toItem(JsonNode node) {
        String type = text(node, "type");
        // A message is the default: the array form of the simple case omits
        // "type" entirely and carries only role + content.
        if (type == null || "message".equals(type)) {
            return ConversationItem.Message.user(contentText(node.get("content")));
        }
        if ("function_call_output".equals(type)) {
            return new ConversationItem.FunctionCallOutput(
                    text(node, "call_id"), text(node, "output"), false);
        }
        // Anything else is a shape this server does not accept as input.
        // Silently dropping it would produce an answer to a question the
        // client did not ask.
        throw new IllegalArgumentException("Input items of type '" + type + "' are not supported");
    }

    /** Content is a string in the simple form and a list of parts otherwise. */
    private String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                String t = text(part, "text");
                if (t != null) {
                    text.append(t);
                }
            }
        }
        return text.toString();
    }

    // ── Response ────────────────────────────────────────────────────────────

    public ResponseDto toDto(Response response, String model) {
        List<ResponseDto.OutputItemDto> output = new ArrayList<>();
        for (ConversationItemRecord record : response.output()) {
            ResponseDto.OutputItemDto item = toOutputItem(record);
            if (item != null) {
                output.add(item);
            }
        }
        return new ResponseDto(
                response.id(),
                "response",
                response.createdAt() == null ? 0 : response.createdAt().getEpochSecond(),
                status(response.status()),
                model,
                output,
                response.outputText(),
                usage(response),
                response.error() == null ? null
                        : new ResponseDto.ErrorDto(response.error().code(), response.error().message()),
                incomplete(response.incompleteReason()),
                response.conversationId() == null ? null
                        : new ResponseDto.ConversationRefDto(response.conversationId()),
                response.parentResponseId(),
                response.metadata() == null || response.metadata().isEmpty() ? null : response.metadata());
    }

    /**
     * One output item. Returns {@code null} for protocol items that have no
     * OpenAI counterpart — the bookkeeping ones a client would not know what
     * to do with.
     */
    public ResponseDto.OutputItemDto toOutputItem(ConversationItemRecord record) {
        String id = record.id();
        return switch (record.item()) {
            case ConversationItem.Message message -> new ResponseDto.OutputItemDto(
                    id, "message", "completed", role(message),
                    List.of(ResponseDto.ContentPartDto.outputText(messageText(message))),
                    null, null, null, null, null);

            case ConversationItem.FunctionCall call -> new ResponseDto.OutputItemDto(
                    id, "function_call", "completed", null, null,
                    call.callId(), call.name(), writeArguments(call.arguments()), null, null);

            case ConversationItem.FunctionCallOutput out -> new ResponseDto.OutputItemDto(
                    id, "function_call_output", out.failed() ? "incomplete" : "completed", null, null,
                    out.callId(), null, null, out.output(), null);

            case ConversationItem.Reasoning reasoning -> new ResponseDto.OutputItemDto(
                    id, "reasoning", "completed", null, null, null, null, null, null,
                    List.of(ResponseDto.ContentPartDto.outputText(reasoning.text())));

            // A sub-agent call is Mindconnect's own; the nearest honest
            // rendering is a function call, so a client at least sees that
            // work was delegated and to whom.
            case ConversationItem.AgentCall agentCall -> new ResponseDto.OutputItemDto(
                    id, "function_call", "completed", null, null,
                    agentCall.callId(), "run_agent",
                    writeArguments(Map.of("agent", agentCall.agentName(), "input", agentCall.input())),
                    null, null);

            default -> null;
        };
    }

    private String messageText(ConversationItem.Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : message.content()) {
            if (part instanceof ContentPart.Text t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    private String role(ConversationItem.Message message) {
        return message.role() == null ? "assistant" : message.role().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** OpenAI sends arguments as a JSON *string*, not as an object. */
    private String writeArguments(Map<String, Object> arguments) {
        try {
            return json.writeValueAsString(arguments == null ? new LinkedHashMap<>() : arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ResponseDto.UsageDto usage(Response response) {
        var usage = response.usage();
        if (usage == null) {
            return null;
        }
        return new ResponseDto.UsageDto(usage.inputTokens(), usage.outputTokens(),
                usage.inputTokens() + usage.outputTokens());
    }

    public static String status(ResponseStatus status) {
        if (status == null) {
            return "in_progress";
        }
        return switch (status) {
            case QUEUED -> "queued";
            case IN_PROGRESS -> "in_progress";
            case COMPLETED -> "completed";
            case INCOMPLETE -> "incomplete";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    /**
     * OpenAI knows two reasons; Mindconnect has more, because it can pause a
     * turn for a human. The ones without a counterpart keep their own name —
     * a client that does not recognise it still sees that the response is
     * incomplete and why, which is better than a wrong familiar word.
     */
    private ResponseDto.IncompleteDetailsDto incomplete(IncompleteReason reason) {
        if (reason == null) {
            return null;
        }
        String name = switch (reason) {
            case MAX_OUTPUT_TOKENS -> "max_output_tokens";
            case MAX_ROUNDS -> "max_tool_calls";
            default -> reason.name().toLowerCase(java.util.Locale.ROOT);
        };
        return new ResponseDto.IncompleteDetailsDto(name);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
