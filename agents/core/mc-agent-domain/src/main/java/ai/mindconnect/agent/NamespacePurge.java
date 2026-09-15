package ai.mindconnect.agent;

/**
 * What a store does when a namespace is deleted: remove everything it kept
 * for that namespace — files, rows, cached adapters, open connections.
 *
 * <p>Deleting a namespace is the one operation that reaches across every
 * store at once, so it is a port each store implements for itself rather
 * than a method on the stores' own interfaces. The namespace service calls
 * every purge it was given, then drops the namespace's record; a purge that
 * fails keeps the record, so the deletion can be tried again.
 */
@FunctionalInterface
public interface NamespacePurge {

    /** Removes everything this store holds for {@code namespace}. Must tolerate a namespace it never saw. */
    void purge(Namespace namespace);
}
