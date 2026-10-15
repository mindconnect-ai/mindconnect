package ai.mindconnect.namespace.domain;

/**
 * What somebody may do in a namespace. There is no third value: either a
 * namespace lists you, and then in exactly one of its two lists, or you are
 * not a member at all — which is {@code Optional.empty()} at every place that
 * asks (see {@link NamespaceDefinition#role}).
 *
 * <p>The distinction is "who shapes the namespace" versus "who works in it".
 * An {@link #ADMIN} creates and changes what the namespace holds — agents, LLM
 * configs, workflows, its variables — and decides who else is in it. A
 * {@link #USER} uses it: chatting, and running the workflows that are there.
 */
public enum NamespaceRole {

    /** Shapes the namespace: its content, its variables, its people. */
    ADMIN,

    /** Works in it: chat, and running workflows — nothing that changes the namespace. */
    USER;

    /**
     * The role a form or an API call asked for. Nothing given is {@link #USER}:
     * the smaller of the two, so a caller that forgot the field does not hand
     * the namespace to somebody.
     *
     * @throws IllegalArgumentException when {@code raw} is neither role
     */
    public static NamespaceRole of(String raw) {
        if (raw == null || raw.isBlank()) return USER;
        try {
            return valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + raw.strip() + "' is not a role — user or admin");
        }
    }
}
