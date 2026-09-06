package ai.mindconnect.llm.domain;

/**
 * One block of an {@link LlmMessage}'s content, as a gateway renders it into
 * the provider's content array. Media travels inline as base64 — the gateway
 * is the last stop before the wire, so this is where bytes belong; the
 * conversation records upstream carry references only.
 *
 * <p>Text-only messages are one {@link Text}; the gateways render those as a
 * plain string, exactly as before content blocks existed, so a provider that
 * never learned arrays (a local server behind an OpenAI-compatible endpoint)
 * sees the same request it always did.
 */
public sealed interface LlmContent {

    /** A run of text. */
    record Text(String text) implements LlmContent {
        public Text {
            if (text == null) text = "";
        }
    }

    /** An image, base64-encoded, with its media type ({@code image/png}, …). */
    record Image(String base64, String mediaType) implements LlmContent {}

    /** A document, base64-encoded, with its media type and the name the provider shows. */
    record Document(String base64, String mediaType, String name) implements LlmContent {}
}
