package ai.mindconnect.agent.responses.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The body of {@code POST /v1/responses}, as an OpenAI client sends it.
 *
 * <p>Unknown fields are ignored rather than rejected. A client SDK sends what
 * its own version knows about, and refusing a request over a field this
 * server has no opinion on would make the API usable only from the SDK
 * version it was written against.
 *
 * @param model               which agent answers — see
 *                            {@code ModelResolver}. An OpenAI client has no
 *                            other field to choose with.
 * @param input               a bare string, or the array form of items.
 * @param instructions        per-request system prompt; overrides the
 *                            agent's own for this response.
 * @param stream              {@code true} switches the response to SSE.
 * @param previousResponseId  continues the conversation that response
 *                            belongs to.
 * @param conversation        a conversation id — a Mindconnect session.
 * @param background          run detached; the client subscribes separately.
 * @param tools               client-side tool definitions. Accepted into the
 *                            model but refused at execution: this runtime
 *                            runs tools inside the turn.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateResponseRequest(
        @JsonProperty("model") String model,
        @JsonProperty("input") JsonNode input,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("previous_response_id") String previousResponseId,
        @JsonProperty("conversation") JsonNode conversation,
        @JsonProperty("background") Boolean background,
        @JsonProperty("store") Boolean store,
        @JsonProperty("metadata") Map<String, Object> metadata,
        @JsonProperty("tools") List<JsonNode> tools
) {

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    public boolean detached() {
        return Boolean.TRUE.equals(background);
    }

    /**
     * The conversation id, whichever way the client spelled it. The field is
     * a string in most SDKs and an object with an {@code id} in some — both
     * appear in OpenAI's own examples.
     */
    public String conversationId() {
        if (conversation == null || conversation.isNull()) {
            return null;
        }
        if (conversation.isTextual()) {
            return conversation.asText();
        }
        JsonNode id = conversation.get("id");
        return id != null && id.isTextual() ? id.asText() : null;
    }
}
