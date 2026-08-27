package ai.mindconnect.pathaccessor;

/**
 * Strategy for reading a dot-separated path from a root object.
 *
 * <p>Implementations are tried in registration order by {@link StringVariableReplacer}.
 * The first accessor whose {@link #supports} method returns {@code true} for the
 * root object is used to resolve the path.
 *
 * <p>Built-in implementations: {@link MapPathAccessor}, {@link BeanPathAccessor},
 * {@link RecordPathAccessor}.  Additional implementations can be added from other
 * modules and registered on the {@link StringVariableReplacer} instance.
 */
public interface PathAccessor {

    /**
     * Returns {@code true} if this accessor knows how to traverse {@code root}.
     */
    boolean supports(Object root);

    /**
     * Reads {@code path} (e.g. {@code "address.city"}) from {@code root}.
     * Segments are dot-separated.  Returns {@code null} when any segment is not found.
     */
    Object read(Object root, String path);
}
