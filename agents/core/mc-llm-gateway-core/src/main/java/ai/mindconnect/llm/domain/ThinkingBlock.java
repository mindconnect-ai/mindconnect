package ai.mindconnect.llm.domain;

/**
 * A reasoning ("thinking") block emitted by a model in an assistant turn.
 * Anthropic-specific: Claude 4.x with adaptive thinking produces these, each
 * carrying a cryptographic {@code signature}. When such a turn also contains
 * tool calls and is replayed in conversation history, the thinking blocks MUST
 * be sent back unchanged (signature included) as the <em>first</em> content
 * blocks of the assistant turn — otherwise the API rejects the request with
 * HTTP 400.
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
