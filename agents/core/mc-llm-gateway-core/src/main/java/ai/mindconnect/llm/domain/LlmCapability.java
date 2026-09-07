package ai.mindconnect.llm.domain;

/**
 * What a chat model can take in and do, as declared on its {@link LlmConfig}
 * or, when the config declares nothing, taken from its provider's default
 * ({@link LlmProvider#defaultCapabilities()}). {@link #VISION} and
 * {@link #DOCUMENTS} steer the runtime: the message mapper sends an image or
 * PDF to the model as content when the config has the capability, and as a
 * placeholder line when it does not. {@link #TOOL_CALLING} and
 * {@link #AUDIO_INPUT} are declarative for now — shown, readable through
 * {@link LlmConfig#supports}, not yet acted on.
 *
 * <p>Only meaningful for {@link LlmConfigType#CHAT} configs; embedding models
 * have no input modalities to declare.
 */
public enum LlmCapability {
    /** The model can call tools (function calling). */
    TOOL_CALLING("Tool calling", "The model can call tools (function calling)"),
    /** The model reads images sent as message content. */
    VISION("Vision", "The model reads images (PNG, JPEG, WebP, GIF) sent with a message"),
    /** The model reads documents (PDF) sent as message content. */
    DOCUMENTS("Documents", "The model reads documents such as PDF sent with a message"),
    /** The model takes audio as message content. */
    AUDIO_INPUT("Audio input", "The model takes audio (speech) as message content");

    private final String label;
    private final String description;

    LlmCapability(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Short display name for UIs. */
    public String label() {
        return label;
    }

    /** One line explaining the capability, for hints and detail views. */
    public String description() {
        return description;
    }
}
