package ai.mindconnect.pathaccessor;

import java.util.Map;

/**
 * A {@link PathAccessor} that traverses nested {@link Map} structures.
 *
 * <p>This is the default accessor registered in every {@code StringVariableReplacer}
 * instance.  It handles the common case where workflow variables are plain
 * {@code Map<String, Object>} trees (e.g. parsed JSON objects).
 *
 * <p>Supports indexed access on {@link java.util.List} and array values:
 * {@code ${order.items[0].name}} reads the {@code name} property of the first
 * element of the {@code items} list stored under the {@code order} map key.
 */
public class MapPathAccessor implements PathAccessor {

    @Override
    public boolean supports(Object root) {
        return root instanceof Map;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Object root, String path) {
        PathSegment[] segments = PathSegment.parseAll(path);
        Object current = root;
        for (PathSegment seg : segments) {
            if (current == null) return null;
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(seg.name);
            } else {
                return null;
            }
            current = seg.applyIndex(current);
        }
        return current;
    }
}
