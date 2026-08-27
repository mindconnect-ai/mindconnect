package ai.mindconnect.pathaccessor;

import java.lang.reflect.Array;
import java.util.List;

/**
 * A single segment of a dot-separated path, optionally carrying an index.
 *
 * <p>Examples:
 * <pre>
 *   "name"       → name="name",  index=-1  (no index)
 *   "items[0]"   → name="items", index=0
 *   "tags[2]"    → name="tags",  index=2
 * </pre>
 *
 * <p>Use {@link #parse(String)} to create instances and
 * {@link #applyIndex(Object)} to extract the indexed element from a
 * {@link List} or array after the property has been read.
 */
public class PathSegment {

    /** Raw segment string as it appeared in the path (for error messages). */
    public final String raw;

    /** Property / component / map-key name (without the {@code [n]} suffix). */
    public final String name;

    /** Index to apply after reading the property, or {@code -1} for none. */
    public final int index;

    private PathSegment(String raw, String name, int index) {
        this.raw   = raw;
        this.name  = name;
        this.index = index;
    }

    public boolean hasIndex() {
        return index >= 0;
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    /**
     * Parses a single path segment such as {@code "items[0]"} or {@code "name"}.
     */
    public static PathSegment parse(String segment) {
        int open = segment.indexOf('[');
        if (open == -1) {
            return new PathSegment(segment, segment, -1);
        }
        int close = segment.indexOf(']', open);
        if (close == -1) {
            // Malformed — treat the whole thing as a plain name
            return new PathSegment(segment, segment, -1);
        }
        String name = segment.substring(0, open);
        int index;
        try {
            index = Integer.parseInt(segment.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return new PathSegment(segment, segment, -1);
        }
        return new PathSegment(segment, name, index);
    }

    /**
     * Parses all segments of a dot-separated {@code path}.
     */
    public static PathSegment[] parseAll(String path) {
        String[] parts = path.split("\\.", -1);
        PathSegment[] segments = new PathSegment[parts.length];
        for (int i = 0; i < parts.length; i++) {
            segments[i] = parse(parts[i]);
        }
        return segments;
    }

    // -----------------------------------------------------------------------
    // Index application
    // -----------------------------------------------------------------------

    /**
     * If this segment carries an index, retrieves element {@link #index} from
     * {@code value} (which must be a {@link List} or array).
     * Returns {@code value} unchanged when there is no index.
     * Returns {@code null} when the index is out of bounds.
     */
    public Object applyIndex(Object value) {
        if (!hasIndex() || value == null) return value;

        if (value instanceof List<?> list) {
            return index < list.size() ? list.get(index) : null;
        }
        if (value != null && value.getClass().isArray()) {
            return index < Array.getLength(value) ? Array.get(value, index) : null;
        }
        // Not a list/array — index cannot be applied
        return null;
    }
}
