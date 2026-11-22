package ai.mindconnect.agent.loop.domain;

/**
 * One typed part of a {@link TurnMessage}'s content: a run of text, or media
 * a multimodal model reads next to it. The loop never looks inside a part —
 * it carries them from the {@code TurnJournal} to the {@code LlmProvider},
 * which renders each for its model or replaces it with a placeholder when
 * the model cannot read that kind.
 *
 * <p>The same shape as the agent protocol's content parts, so the API edge
 * maps one to one; this module cannot depend on that vocabulary.
 */
public sealed interface ContentPart {

    record Text(String text) implements ContentPart { }

    /**
     * An image.
     *
     * @param detail vision-encoder hint; {@link Detail#AUTO} when unset
     */
    record Image(MediaSource source, Detail detail) implements ContentPart {

        public static Image of(MediaSource source) {
            return new Image(source, Detail.AUTO);
        }

        /** Resolution hint for the vision encoder. */
        public enum Detail { AUTO, LOW, HIGH }
    }

    /**
     * Audio. The media type travels with the source:
     * {@link MediaSource.Inline#mediaType()} for inline data, resolved by the
     * file store for {@link MediaSource.FileId} sources.
     */
    record Audio(MediaSource source) implements ContentPart { }

    /**
     * A document (PDF, text file, …). {@code name} is the display filename,
     * also used by models that cite the document.
     */
    record Document(MediaSource source, String name) implements ContentPart { }

    /**
     * Where media bytes live. Exactly one of three shapes instead of nullable
     * fields on every media part.
     */
    sealed interface MediaSource {

        /** Fetchable location; whoever renders the part downloads at use time. */
        record Url(String url) implements MediaSource { }

        /** Reference into the file store. */
        record FileId(String fileId) implements MediaSource { }

        /**
         * Inline payload, base64-encoded. A {@code String} rather than
         * {@code byte[]} keeps record value semantics: array fields compare
         * by identity.
         */
        record Inline(String base64Data, String mediaType) implements MediaSource { }
    }
}
