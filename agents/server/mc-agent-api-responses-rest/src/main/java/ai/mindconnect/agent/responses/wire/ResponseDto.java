package ai.mindconnect.agent.responses.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The {@code response} object an OpenAI client expects back, from
 * {@code POST /v1/responses}, {@code GET /v1/responses/{id}}, and inside
 * every lifecycle event of the stream.
 *
 * <p>Nulls are omitted. A client that checks {@code if response.error} must
 * not see an explicit {@code null} turn into a truthy empty object in some
 * language's JSON binding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("status") String status,
        @JsonProperty("model") String model,
        @JsonProperty("output") List<OutputItemDto> output,
        @JsonProperty("output_text") String outputText,
        @JsonProperty("usage") UsageDto usage,
        @JsonProperty("error") ErrorDto error,
        @JsonProperty("incomplete_details") IncompleteDetailsDto incompleteDetails,
        @JsonProperty("conversation") ConversationRefDto conversation,
        @JsonProperty("previous_response_id") String previousResponseId,
        @JsonProperty("metadata") Map<String, Object> metadata
) {

    /** One entry of {@code output} — a message, a function call, a reasoning block. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputItemDto(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("status") String status,
            @JsonProperty("role") String role,
            @JsonProperty("content") List<ContentPartDto> content,
            // function_call
            @JsonProperty("call_id") String callId,
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments,
            // function_call_output
            @JsonProperty("output") String output,
            // reasoning
            @JsonProperty("summary") List<ContentPartDto> summary
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentPartDto(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text,
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("file_id") String fileId,
            @JsonProperty("filename") String filename
    ) {
        public static ContentPartDto outputText(String text) {
            return new ContentPartDto("output_text", text, null, null, null);
        }

        public static ContentPartDto inputText(String text) {
            return new ContentPartDto("input_text", text, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageDto(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("total_tokens") long totalTokens
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDto(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IncompleteDetailsDto(
            @JsonProperty("reason") String reason
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConversationRefDto(
            @JsonProperty("id") String id
    ) { }
}
