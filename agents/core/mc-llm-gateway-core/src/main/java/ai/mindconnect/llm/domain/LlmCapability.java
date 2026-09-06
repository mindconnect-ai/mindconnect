package ai.mindconnect.llm.domain;

/**
 * What a chat model can take in and do, as declared on its {@link LlmConfig}.
 * The set is <em>informative</em>: the admin states it, the UI shows it, and a
 * caller that wants to know whether a model reads images asks the config
 * instead of guessing from the model id. Nothing in the runtime derives its
 * control flow from it yet — a config that leaves the set empty still runs
 * exactly as before.
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
