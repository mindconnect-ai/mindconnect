package ai.mindconnect.agent.protocol.item;

/**
 * One typed part of a {@link ConversationItem.Message}. Mirrors the OpenAI Responses
 * input parts ({@code input_text} / {@code input_image} / {@code input_audio}
 * / {@code input_file}); media parts share the {@link MediaSource} choice of
 * URL, file-store reference or inline data.
 *
 * <p>Output side needs no extra types: assistant text is a {@code Text} part,
 * and <em>generated</em> media arrives as tool-call items (like OpenAI's
 * {@code image_generation_call}), never as message content.
 */
public sealed interface ContentPart {

    record Text(String text) implements ContentPart {}

    /**
     * An image input.
     *
     * @param detail vision-encoder hint; {@link Detail#AUTO} when unset
     */
    record Image(MediaSource source, Detail detail) implements ContentPart {

        public static Image of(MediaSource source) { return new Image(source, Detail.AUTO); }

        /** Resolution hint for the vision encoder. */
        public enum Detail { AUTO, LOW, HIGH }
    }

    /**
     * An audio input. The media type travels with the source:
     * {@link MediaSource.Inline#mediaType()} for inline data (e.g.
     * {@code "audio/mpeg"}, {@code "audio/wav"}), resolved by the file store
     * for {@link MediaSource.FileId} sources.
     */
    record Audio(MediaSource source) implements ContentPart {}

    /**
     * A document input (PDF, text file, …). {@code name} is the display
     * filename, also used by models that cite the document.
     */
    record Document(MediaSource source, String name) implements ContentPart {}

    /**
     * Where media bytes live. Exactly one of three shapes — mirroring
     * OpenAI's url / file_id / base64 choice — instead of nullable fields
     * on every media part.
     */
    sealed interface MediaSource {

        /** Fetchable location; the runtime downloads at use time. */
        record Url(String url) implements MediaSource {}

        /** Reference into the file store (upload first, then reference). */
        record FileId(String fileId) implements MediaSource {}

        /**
         * Inline payload, base64-encoded. A {@code String} rather than
         * {@code byte[]} keeps record value semantics (array fields compare
         * by identity) and matches the wire format anyway.
         */
        record Inline(String base64Data, String mediaType) implements MediaSource {}
    }
}
