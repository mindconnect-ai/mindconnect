package ai.mindconnect.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The request body as a trace or a debug log should carry it: with the
 * media payloads cut out. An image or PDF travels inline as base64 — a
 * megabyte or more per picture — and the {@code LlmCallEvent} is persisted
 * per call, so the verbatim body would grow the trace store by the size of
 * every attachment on every turn. The wire request itself is untouched.
 *
 * <p>Two shapes are recognised, whatever the provider's field names: a data
 * URL ({@code data:image/png;base64,…} — OpenAI's {@code image_url} and
 * {@code file_data}) and a bare base64 string of some length (Anthropic's
 * {@code source.data}, Gemini's {@code inline_data.data}). Both are replaced
 * by a short note that keeps the media type and the payload's length, so
 * the trace still shows what was sent.
 */
public final class TraceRedaction {

    /** A bare string this long that is all base64 characters is treated as a payload. */
    static final int BASE64_MIN_LENGTH = 1024;

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=\\r\\n]+");

    private TraceRedaction() {
    }

    /** A copy of {@code node} with media payloads replaced; the argument is not modified. */
    public static JsonNode redactMedia(JsonNode node) {
        if (node == null) return null;
        JsonNode copy = node.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    private static void redactInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode replacement = redacted(field.getValue());
                if (replacement != null) {
                    field.setValue(replacement);
                } else {
                    redactInPlace(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode replacement = redacted(array.get(i));
                if (replacement != null) {
                    array.set(i, replacement);
                } else {
                    redactInPlace(array.get(i));
                }
            }
        }
    }

    /** The note that stands in for a payload, or {@code null} when the value is not one. */
    private static JsonNode redacted(JsonNode value) {
        if (!value.isTextual()) return null;
        String text = value.asText();
        if (text.startsWith("data:")) {
            int comma = text.indexOf(";base64,");
            if (comma > 0) {
                String mediaType = text.substring("data:".length(), comma);
                return new TextNode("data:" + mediaType + ";base64,<"
                        + (text.length() - comma - ";base64,".length()) + " chars omitted>");
            }
        }
        if (text.length() >= BASE64_MIN_LENGTH && BASE64.matcher(text).matches()) {
            return new TextNode("<base64, " + text.length() + " chars omitted>");
        }
        return null;
    }
}
