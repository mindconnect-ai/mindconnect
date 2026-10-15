package ai.mindconnect.llm.domain;

/**
 * A reasoning block of an assistant turn, kept opaque: no field is interpreted,
 * all are carried through stream, persistence, history and wire unchanged. Only
 * Anthropic's blocks carry a {@code signature}, and when such a turn also calls
 * tools the API rejects the replayed history unless the signed blocks come back
 * unchanged as the first content blocks; unsigned blocks are for reading only. A
 * {@code redacted_thinking} block has encrypted {@link #data} and no text.
 */
public record ThinkingBlock(
        String type,
        String text,
        String data,
        String signature
) {}
