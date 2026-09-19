package ai.mindconnect.agent.tool;

/**
 * One account a user attached, as a tool sees it.
 *
 * <p>The narrow view on purpose. The entity, its credentials, its encryption
 * and its store live in {@code mc-credentials}; a tool needs none of that, and
 * if this interface were that record, every tool jar would carry the
 * credential store with it. A tool asks for values by name and is done.
 *
 * <p>{@link #value} reads across the readable settings and the secret half
 * alike — which side a field lives on is decided by the provider's schema, and
 * is nothing a tool should have to know.
 */
public interface ToolConnection {

    /** Stable, lowercase, what a call names: {@code "arbeit"}. */
    String key();

    /** What the user called it: {@code "Arbeit"}. */
    String label();

    /** What it connects to — matches {@link ConnectionSpec#provider()}. */
    String provider();

    /** The value of one field of the provider's schema, or null. */
    String value(String field);

    /** True when nothing is known to be wrong with it. */
    boolean usable();
}
