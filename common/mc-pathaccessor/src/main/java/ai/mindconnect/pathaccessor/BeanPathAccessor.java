package ai.mindconnect.pathaccessor;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * A {@link PathAccessor} that traverses JavaBean property hierarchies using
 * standard getter conventions ({@code getXxx()} / {@code isXxx()} for booleans).
 *
 * <p>Handles mixed trees: if an intermediate value turns out to be a {@link Map},
 * the remaining path segments are resolved via map key lookup instead of reflection.
 *
 * <p>Supports indexed access on {@link java.util.List} and array values:
 * {@code ${customer.orders[1].total}} reads {@code total} from the second element
 * of the {@code orders} list returned by {@code customer.getOrders()}.
 */
public class BeanPathAccessor implements PathAccessor {

    @Override
    public boolean supports(Object root) {
        if (root == null) return false;
        Class<?> cls = root.getClass();
        return !cls.isPrimitive()
                && !(root instanceof Map)
                && !(root instanceof String)
                && !(root instanceof Number)
                && !(root instanceof Boolean)
                && !cls.isRecord();
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
                current = readProperty(current, seg.name);
            }
            current = seg.applyIndex(current);
        }
        return current;
    }

    // -----------------------------------------------------------------------
    // Getter resolution
    // -----------------------------------------------------------------------

    private Object readProperty(Object target, String name) {
        Class<?> cls = target.getClass();

        Method getter = findMethod(cls, getterName("get", name));
        if (getter != null) return invoke(getter, target);

        getter = findMethod(cls, getterName("is", name));
        if (getter != null) return invoke(getter, target);

        // Fallback: exact method name (covers record-like accessor style)
        getter = findMethod(cls, name);
        if (getter != null) return invoke(getter, target);

        return null;
    }

    private static String getterName(String prefix, String property) {
        if (property.isEmpty()) return prefix;
        return prefix + Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            return m.getParameterCount() == 0 ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target) {
        try {
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to read property via " + method.getName() + ": " + e.getMessage(), e);
        }
    }
}
