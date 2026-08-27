package ai.mindconnect.workflow.scripting;

import ai.mindconnect.workflow.execution.VariableScope;

import javax.script.Bindings;
import java.util.*;

/**
 * A JSR-223 {@link Bindings} implementation backed directly by a
 * {@link VariableScope}.
 *
 * <p>Unlike {@link javax.script.SimpleBindings}, reads and writes go straight
 * into the live scope — no snapshot copy is needed.  Any variable a script
 * writes is immediately visible in the workflow's variable hierarchy without
 * a separate export step.
 *
 * <p>An optional <em>extras</em> map holds objects that should be visible in
 * scripts (e.g. a {@code JSON} helper) but must not pollute the workflow scope.
 */
public class VariableScopeBinding implements Bindings {

    private final VariableScope variableScope;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public VariableScopeBinding(VariableScope variableScope) {
        this.variableScope = variableScope;
    }

    // -----------------------------------------------------------------------
    // Extra bindings (utilities visible in scripts, not in workflow scope)
    // -----------------------------------------------------------------------

    /**
     * Puts an object that is visible to scripts via {@code name} but is
     * <em>not</em> stored in the workflow variable scope.
     */
    public void putExtra(String name, Object value) {
        extras.put(name, value);
    }

    // -----------------------------------------------------------------------
    // Bindings / Map interface
    // -----------------------------------------------------------------------

    @Override
    public Object put(String name, Object value) {
        variableScope.assignValue(name, value, null);
        return value;
    }

    @Override
    public void putAll(Map<? extends String, ?> toMerge) {
        if (toMerge != null) {
            toMerge.forEach((k, v) -> variableScope.assignValue(k, v, null));
        }
    }

    @Override
    public Object get(Object key) {
        if (extras.containsKey(key)) {
            return extras.get(key);
        }
        return variableScope.getVariableValue((String) key);
    }

    @Override
    public boolean containsKey(Object key) {
        return extras.containsKey(key) || variableScope.getVariableValueMap().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return extras.containsValue(value) || variableScope.getVariableValueMap().containsValue(value);
    }

    @Override
    public Set<String> keySet() {
        Set<String> keys = new LinkedHashSet<>(variableScope.getVariableValueMap().keySet());
        keys.addAll(extras.keySet());
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public Collection<Object> values() {
        List<Object> vals = new ArrayList<>(variableScope.getVariableValueMap().values());
        vals.addAll(extras.values());
        return Collections.unmodifiableList(vals);
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        Map<String, Object> combined = new LinkedHashMap<>(variableScope.getVariableValueMap());
        combined.putAll(extras);
        return Collections.unmodifiableSet(combined.entrySet());
    }

    @Override
    public Object remove(Object key) {
        return extras.remove(key);
    }

    @Override
    public void clear() {
        extras.clear();
    }

    @Override
    public int size() {
        return variableScope.getVariableValueMap().size() + extras.size();
    }

    @Override
    public boolean isEmpty() {
        return variableScope.getVariableValueMap().isEmpty() && extras.isEmpty();
    }
}
