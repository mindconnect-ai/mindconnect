package ai.mindconnect.pathaccessor;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;

/**
 * A {@link PathAccessor} that traverses Java {@code record} hierarchies.
 *
 * <p>Records expose their components via no-arg accessor methods whose name
 * matches the component name exactly (no {@code get} prefix).  This accessor
 * uses {@link Class#getRecordComponents()} to enumerate available components,
 * then invokes the matching accessor method.
 *
 * <p>Handles mixed trees: if an intermediate value is a {@link Map} the
 * remaining segments are resolved by map key lookup.
 *
 * <p>Supports indexed access on {@link java.util.List} and array values:
 * {@code ${shipment.packages[0].weight}} reads {@code weight} from the first
 * element of the {@code packages} component of the {@code shipment} record.
 */
public class RecordPathAccessor implements PathAccessor {

    @Override
    public boolean supports(Object root) {
        return root != null && root.getClass().isRecord();
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
            } else if (current.getClass().isRecord()) {
                current = readComponent(current, seg.name);
            } else {
                return null;
            }
            current = seg.applyIndex(current);
        }
        return current;
    }

    // -----------------------------------------------------------------------
    // Component resolution
    // -----------------------------------------------------------------------

    private Object readComponent(Object record, String name) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        if (components == null) return null;

        for (RecordComponent component : components) {
            if (component.getName().equals(name)) {
                return invoke(component.getAccessor(), record);
            }
        }
        return null;
    }

    private static Object invoke(Method accessor, Object record) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to read record component via " + accessor.getName()
                            + ": " + e.getMessage(), e);
        }
    }
}
