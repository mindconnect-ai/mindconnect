package ai.mindconnect.agent.loop.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * The one message the loop knows: what a conversation holds and what a round
 * produces are the same type. A message a round produced carries no
 * {@code id} and no {@code turnId} yet: the loop puts it into its turn
 * ({@link #inTurn}), the {@code TurnJournal} assigns the id, and what that
 * hands back is the stored message ({@link #stored}).
 *
 * <p>Nothing here is a wire or storage format. The content is a list of
 * typed parts ({@link ContentPart}) — text, and the images, audio and
 * documents a multimodal model reads next to it. Tool calls are structured
 * ({@link ToolCall}), a tool result's content is the plain output; which
 * JSON a store or a provider wants is the adapter's business.
 *
 * <p>The factories encode the metadata contract the {@link ToolCalls} fold
 * pairs on: {@code callId} on {@link Type#TOOL_DISPATCHED} and
 * {@link Type#TOOL_RESULT}, {@code callIds} on {@link Type#TOOL_CALL}. An
 * adapter that maps stored messages into this type must fill those keys.
 * The loop adds its own: {@code inputTokens} and {@code outputTokens} on the
 * message a model call produced ({@link #withUsage}), from which a turn's
 * progress is counted. Beyond them the metadata is the adapter's to extend —
 * reasoning blocks a provider wants back, measured durations.
 *
 * @param id        the store's id of this message; {@code null} until stored
 * @param turnId    the turn that wrote this message — the request id of
 *                  that turn; {@code null} on a message no turn has taken
 *                  yet. A user message with the id of the turn already
 *                  open continues that turn instead of opening one
 * @param content   the parts in the order they were said; empty for a
 *                  message that says nothing itself (a dispatch marker, a
 *                  bare tool-call request)
 * @param toolCalls the calls a {@link Type#TOOL_CALL} message requests; empty
 *                  for every other type
 */
public record TurnMessage(
        String id,
        String turnId,
        Type type,
        List<ContentPart> content,
        List<ToolCall> toolCalls,
        Map<String, Object> metadata
) {

    /** What a message is — sender and kind in one, as far as the loop tells them apart. */
    public enum Type {

        /** What the user said. Opens a turn, and with it a new episode of tool calls. */
        USER,

        /** The model's answer text. */
        ASSISTANT,

        /** The model requests tool calls: {@link TurnMessage#toolCalls()}. */
        TOOL_CALL,

        /**
         * Marker: execution of {@code metadata.callId} has started — written
         * BEFORE the executor is asked, so a resume can tell "never ran" from
         * "may have run" and a non-idempotent tool is not run twice.
         */
        TOOL_DISPATCHED,

        /** Result of {@code metadata.callId}; {@code metadata.failed} says whether it worked. */
        TOOL_RESULT
    }

    /** One call the model asked for. Arguments may hold {@code null} values, as JSON does. */
    public record ToolCall(String callId, String name, Map<String, Object> arguments) {

        public ToolCall {
            arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    public TurnMessage {
        content = List.copyOf(content);
        toolCalls = List.copyOf(toolCalls);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** What the user said — text, and the media sent with it. */
    public static TurnMessage user(List<ContentPart> content) {
        return new TurnMessage(null, null, Type.USER, content, List.of(), Map.of());
    }

    /** The agent's (final) answer text. */
    public static TurnMessage assistant(String text) {
        return new TurnMessage(null, null, Type.ASSISTANT, textPart(text), List.of(), Map.of());
    }

    /** The assistant turn that requested tool calls. */
    public static TurnMessage toolCalls(List<ToolCall> calls) {
        return new TurnMessage(null, null, Type.TOOL_CALL, List.of(), calls,
                Map.of("callIds", calls.stream().map(ToolCall::callId).toList()));
    }

    /** Marker: {@code callId} is being executed — persisted BEFORE the executor is asked. */
    public static TurnMessage dispatched(String callId) {
        return new TurnMessage(null, null, Type.TOOL_DISPATCHED, List.of(), List.of(), Map.of("callId", callId));
    }

    /** Result of one tool call; {@code failed} lets the model react to errors. */
    public static TurnMessage toolResult(String callId, String toolName, String output, boolean failed) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callId", callId);
        if (toolName != null) metadata.put("toolName", toolName);
        metadata.put("failed", failed);
        return new TurnMessage(null, null, Type.TOOL_RESULT, textPart(output == null ? "" : output),
                List.of(), metadata);
    }

    /**
     * The text of the content — its text parts joined in order. Media parts
     * contribute nothing; a message that is only an image has empty text.
     */
    public String text() {
        return content.stream()
                .filter(ContentPart.Text.class::isInstance)
                .map(part -> ((ContentPart.Text) part).text())
                .collect(Collectors.joining("\n"));
    }

    /** Copy with one metadata entry added — e.g. the measured {@code durationMs}. */
    public TurnMessage with(String key, Object value) {
        Map<String, Object> extended = new LinkedHashMap<>(metadata);
        extended.put(key, value);
        return new TurnMessage(id, turnId, type, content, toolCalls, extended);
    }

    /** Copy as part of the turn that writes it. */
    public TurnMessage inTurn(String turnId) {
        return new TurnMessage(id, turnId, type, content, toolCalls, metadata);
    }

    /** Copy as the journal stored it: with the id only the store assigns. */
    public TurnMessage stored(String id) {
        return new TurnMessage(id, turnId, type, content, toolCalls, metadata);
    }

    /** Copy that says what the model call that produced this message cost. */
    public TurnMessage withUsage(Usage usage) {
        return with("inputTokens", usage.inputTokens()).with("outputTokens", usage.outputTokens());
    }

    /**
     * What the model call that produced this message cost; empty on a
     * message no model call produced. Reads any number: a store that keeps
     * metadata as JSON gives back an {@code Integer} for a small {@code long}.
     */
    public Optional<Usage> usage() {
        if (metadata.get("inputTokens") instanceof Number input
                && metadata.get("outputTokens") instanceof Number output) {
            return Optional.of(new Usage(input.longValue(), output.longValue()));
        }
        return Optional.empty();
    }

    private static List<ContentPart> textPart(String text) {
        return List.of(new ContentPart.Text(text));
    }
}
