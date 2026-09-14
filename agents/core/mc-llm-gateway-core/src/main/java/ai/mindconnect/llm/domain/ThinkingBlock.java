package ai.mindconnect.llm.domain;

/**
 * A reasoning ("thinking") block emitted by a model in an assistant turn.
 * Every reasoning model produces them — Claude with adaptive thinking, and
 * the open models behind OpenAI-compatible servers (Qwen3, DeepSeek-R1,
 * gpt-oss) whose reasoning arrives as {@code reasoning_content} /
 * {@code reasoning} or inline {@code <think>} tags. Only Anthropic's carry a
 * cryptographic {@code signature}: when such a turn also contains tool calls
 * and is replayed in conversation history, the signed blocks MUST be sent
 * back unchanged as the <em>first</em> content blocks of the assistant turn —
 * otherwise the API rejects the request with HTTP 400. Unsigned blocks are
 * for reading only and are never replayed.
 * <p>
 * Two shapes:
 * <ul>
 *   <li>{@code type="thinking"} — readable {@link #text} + {@link #signature}.</li>
 *   <li>{@code type="redacted_thinking"} — encrypted {@link #data}; {@code text}
 *       is null. Pass through bit-for-bit; never decode.</li>
 * </ul>
 * Kept opaque on purpose — no field is interpreted, only carried through the
 * stream → domain → persistence → history → wire round-trip.
 */
public record ThinkingBlock(
        String type,
        String text,
        String data,
        String signature
) {}
