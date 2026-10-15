package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseError;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Usage;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.protocol.item.Role;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Translates between the protocol vocabulary and OpenAI's wire JSON. Stateless. */
final class OpenAiMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiMapper() { }

    // ── protocol → OpenAI ───────────────────────────────────────────────────

    static Map<String, Object> functionToolJson(ToolDefinition tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "function");
        m.put("name", tool.name());
        if (tool.description() != null) m.put("description", tool.description());
        m.put("parameters", tool.parametersSchema());
        return m;
    }

    static Map<String, Object> inputItemJson(ConversationItem item) {
        return switch (item) {
            case ConversationItem.Message m -> Map.of(
                    "type", "message",
                    "role", m.role().name().toLowerCase(Locale.ROOT),
                    "content", m.content().stream().map(OpenAiMapper::inputContentJson).toList());
            case ConversationItem.FunctionCallOutput o -> Map.of(
                    "type", "function_call_output",
                    "call_id", o.callId(),
                    "output", o.output());
            default -> throw new OpenAiBackendException(
                    "Input item not supported by the OpenAI backend: "
                            + item.getClass().getSimpleName(), null);
        };
    }

    private static Map<String, Object> inputContentJson(ContentPart part) {
        return switch (part) {
            case ContentPart.Text t -> Map.of("type", "input_text", "text", t.text());
            case ContentPart.Image img -> switch (img.source()) {
                case ContentPart.MediaSource.Url u ->
                        Map.of("type", "input_image", "image_url", u.url());
                case ContentPart.MediaSource.Inline in -> Map.of("type", "input_image",
                        "image_url", "data:" + in.mediaType() + ";base64," + in.base64Data());
                case ContentPart.MediaSource.FileId f ->
                        Map.of("type", "input_image", "file_id", f.fileId());
            };
            case ContentPart.Audio a -> switch (a.source()) {
                case ContentPart.MediaSource.Inline in -> Map.of("type", "input_audio",
                        "input_audio", Map.of("data", in.base64Data(),
                                "format", audioFormat(in.mediaType())));
                default -> throw new OpenAiBackendException(
                        "Audio needs an Inline source for the OpenAI backend", null);
            };
            case ContentPart.Document d -> switch (d.source()) {
                case ContentPart.MediaSource.Inline in -> Map.of("type", "input_file",
                        "filename", d.name(),
                        "file_data", "data:" + in.mediaType() + ";base64," + in.base64Data());
                case ContentPart.MediaSource.Url u ->
                        Map.of("type", "input_file", "file_url", u.url());
                case ContentPart.MediaSource.FileId f ->
                        Map.of("type", "input_file", "file_id", f.fileId());
            };
        };
    }

    private static String audioFormat(String mediaType) {
        if (mediaType == null) return "mp3";
        return switch (mediaType) {
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            default -> mediaType.replace("audio/", "");
        };
    }

    // ── OpenAI → protocol ───────────────────────────────────────────────────

    /**
     * Maps a response. {@code functionToolNames} are the names of the
     * DECLARED function tools — only calls to those can be "open": hosted
     * tool calls (web_search_call, …) run inside OpenAI, their names never
     * match, so they never trigger {@code WAITING_FOR_TOOL_OUTPUT}.
     */
    static Response response(JsonNode n, String sessionId, String agentName,
                             Set<String> functionToolNames) {
        List<ConversationItemRecord> output = new ArrayList<>();
        int i = 0;
        for (JsonNode itemNode : n.path("output")) {
            i++;
            String itemId = itemNode.path("id").asText("item-" + i);
            output.add(new ConversationItemRecord(itemId, i, outputItem(itemNode)));
        }
        ResponseStatus status = status(n.path("status").asText());
        IncompleteReason reason = status == ResponseStatus.INCOMPLETE ? incompleteReason(n) : null;

        Response mapped = new Response(
                n.path("id").asText(), conversationId(n), sessionId, agentName,
                status, reason, null, null, List.copyOf(output),
                usage(n), error(n), metadata(n), createdAt(n),
                status.terminal() ? Instant.now() : null);

        // Without a runtime, every declared function tool is client-executed:
        // a "completed" response ending on an open declared call actually waits.
        boolean waitingForTools = status == ResponseStatus.COMPLETED
                && mapped.openFunctionCalls().stream()
                        .anyMatch(call -> functionToolNames.contains(call.name()));
        if (!waitingForTools) return mapped;
        return new Response(mapped.id(), mapped.conversationId(), sessionId, agentName,
                ResponseStatus.INCOMPLETE, IncompleteReason.WAITING_FOR_TOOL_OUTPUT,
                null, null, mapped.output(), mapped.usage(), mapped.error(),
                mapped.metadata(), mapped.createdAt(), mapped.completedAt());
    }

    static ConversationItem outputItem(JsonNode n) {
        String type = n.path("type").asText();
        return switch (type) {
            case "message" -> new ConversationItem.Message(
                    role(n.path("role").asText("assistant")), content(n.path("content")));
            case "function_call" -> new ConversationItem.FunctionCall(
                    n.path("call_id").asText(), n.path("name").asText(),
                    parseArguments(n.path("arguments").asText("{}")));
            case "function_call_output" -> new ConversationItem.FunctionCallOutput(
                    n.path("call_id").asText(), n.path("output").asText(), false);
            case "reasoning" -> new ConversationItem.Reasoning(reasoningText(n), null);
            // Hosted tool calls (web_search_call, file_search_call, …) stay
            // visible as calls; their names never match a declared function
            // tool, so they can never look "open".
            default -> new ConversationItem.FunctionCall(n.path("id").asText(type), type, Map.of());
        };
    }

    static Optional<ResponseEvent> event(JsonNode n, String responseId) {
        long seq = n.path("sequence_number").asLong(0);
        return Optional.ofNullable(switch (n.path("type").asText()) {
            case "response.created" -> new ResponseEvent.Created(responseId, seq);
            case "response.in_progress" -> new ResponseEvent.InProgress(responseId, seq);
            case "response.completed" -> new ResponseEvent.Completed(responseId, seq, usage(n.path("response")));
            case "response.failed" -> new ResponseEvent.Failed(responseId, seq, error(n.path("response")));
            case "response.incomplete" -> new ResponseEvent.Incomplete(responseId, seq, incompleteReason(n.path("response")));
            case "response.output_item.added" -> new ResponseEvent.OutputItemAdded(responseId, seq, entry(n));
            case "response.output_item.done" -> new ResponseEvent.OutputItemDone(responseId, seq, entry(n));
            case "response.output_text.delta" -> new ResponseEvent.OutputTextDelta(
                    responseId, seq, n.path("item_id").asText(), n.path("delta").asText());
            case "response.function_call_arguments.delta" -> new ResponseEvent.ArgumentsDelta(
                    responseId, seq, n.path("item_id").asText(), n.path("delta").asText());
            case "response.reasoning_summary_text.delta" -> new ResponseEvent.ReasoningDelta(
                    responseId, seq, n.path("item_id").asText(), n.path("delta").asText());
            default -> null;
        });
    }

    static ResponseStatus status(String s) {
        return switch (s) {
            case "queued" -> ResponseStatus.QUEUED;
            case "completed" -> ResponseStatus.COMPLETED;
            case "failed" -> ResponseStatus.FAILED;
            case "cancelled" -> ResponseStatus.CANCELLED;
            case "incomplete" -> ResponseStatus.INCOMPLETE;
            default -> ResponseStatus.IN_PROGRESS;
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ConversationItemRecord entry(JsonNode n) {
        JsonNode itemNode = n.path("item");
        return new ConversationItemRecord(itemNode.path("id").asText("item"),
                n.path("output_index").asLong(0) + 1, outputItem(itemNode));
    }

    private static Role role(String s) {
        return switch (s) {
            case "user" -> Role.USER;
            case "system", "developer" -> Role.SYSTEM;
            default -> Role.ASSISTANT;
        };
    }

    private static List<ContentPart> content(JsonNode contentNode) {
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode part : contentNode) {
            switch (part.path("type").asText()) {
                case "output_text", "input_text" -> parts.add(new ContentPart.Text(part.path("text").asText()));
                case "refusal" -> parts.add(new ContentPart.Text(part.path("refusal").asText()));
                default -> { }   // annotations, images etc. — not mapped in v1
            }
        }
        return List.copyOf(parts);
    }

    private static Map<String, Object> parseArguments(String argsJson) {
        try {
            return JSON.readValue(argsJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of("_raw", argsJson);
        }
    }

    private static String reasoningText(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode summary : n.path("summary")) {
            sb.append(summary.path("text").asText());
        }
        return sb.toString();
    }

    private static IncompleteReason incompleteReason(JsonNode n) {
        // OpenAI only reports output-side truncation reasons here
        return IncompleteReason.CONTEXT_OVERFLOW;
    }

    private static Usage usage(JsonNode n) {
        JsonNode u = n.path("usage");
        return new Usage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0));
    }

    private static ResponseError error(JsonNode n) {
        JsonNode e = n.path("error");
        if (e.isMissingNode() || e.isNull()) return null;
        return new ResponseError(e.path("code").asText("error"), e.path("message").asText());
    }

    /** The extension slot: provider specifics as data under {@code openai.*} keys. */
    private static Map<String, Object> metadata(JsonNode n) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (n.hasNonNull("model")) metadata.put("openai.model", n.path("model").asText());
        JsonNode cached = n.path("usage").path("input_tokens_details").path("cached_tokens");
        if (cached.isNumber()) metadata.put("openai.cachedTokens", cached.asLong());
        JsonNode reasoning = n.path("usage").path("output_tokens_details").path("reasoning_tokens");
        if (reasoning.isNumber()) metadata.put("openai.reasoningTokens", reasoning.asLong());
        return Map.copyOf(metadata);
    }

    private static String conversationId(JsonNode n) {
        JsonNode conv = n.path("conversation");
        String id = conv.isObject() ? conv.path("id").asText(null) : conv.asText(null);
        return id == null || id.isEmpty() ? null : id;
    }

    private static Instant createdAt(JsonNode n) {
        return n.has("created_at") ? Instant.ofEpochSecond(n.path("created_at").asLong()) : null;
    }
}
