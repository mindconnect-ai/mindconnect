package ai.mindconnect.workflow.execution;

import lombok.Data;

import java.util.*;

/**
 * Hierarchical variable store. Each step container owns one scope;
 * variable lookup walks up the parent chain until a match is found.
 */
@Data
public class VariableScope {

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    @Data
    public static class VariableHistory {
        private Object value;
        private Object previousValue;
        private long created;
    }

    @Data
    public static class Variable {
        private String name;
        private Object value;
        private boolean constant = false;
        private long created;
        private long updated;
        private String scopeName;
        private List<VariableHistory> historyList = new ArrayList<>();

        /** For deserialization. */
        public Variable() {}

        public Variable(String name, String scopeName) {
            this.name = name;
            this.scopeName = scopeName;
            this.created = System.currentTimeMillis();
        }

        public void setValue(Object value) {
            VariableHistory history = new VariableHistory();
            history.setPreviousValue(this.value);
            history.setValue(value);
            long now = System.currentTimeMillis();
            history.setCreated(now);
            this.value = value;
            this.updated = now;
            this.historyList.add(history);
        }

        public String toShortString() {
            return scopeName + ":(" + name + "=" + value + ")";
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private VariableScope parentScope;
    private String scopeName;
    private Map<String, Variable> variablesMap = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** For deserialization. */
    public VariableScope() {}

    public VariableScope(VariableScope parentScope, String scopeName) {
        this.parentScope = parentScope;
        this.scopeName = scopeName;
    }

    // -----------------------------------------------------------------------
    // Assignment
    // -----------------------------------------------------------------------

    public Variable assignValue(String name, Object value, ExpressionResolver expressionResolver) {
        Variable variable = getVariable(name);
        if (variable == null) {
            variable = new Variable(name, this.scopeName);
            variablesMap.put(name, variable);
        }
        if (expressionResolver != null && expressionResolver.containsExpression(value)) {
            variable.setValue(expressionResolver.eval(this, String.valueOf(value)));
        } else {
            variable.setValue(value);
        }
        return variable;
    }

    public Variable assignValueToParentScope(String name, Object value, ExpressionResolver resolver) {
        if (parentScope != null) {
            return parentScope.assignValue(name, value, resolver);
        }
        throw new IllegalStateException(
                "Cannot assign variable '" + name + "' to parent scope: no parent exists for scope '" + scopeName + "'");
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /** Walks up the scope chain; returns null when not found anywhere. */
    public Variable getVariable(String name) {
        Variable variable = variablesMap.get(name);
        if (variable == null && parentScope != null) {
            variable = parentScope.getVariable(name);
        }
        return variable;
    }

    public Object getVariableValue(String name) {
        Variable variable = getVariable(name);
        return variable == null ? null : variable.getValue();
    }

    /** Convenience alias. */
    public Object get(String name) {
        return getVariableValue(name);
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------

    /** All variables visible in this scope, including those from parent scopes. */
    public Collection<Variable> getVariables() {
        List<Variable> result = new ArrayList<>();
        if (parentScope != null) {
            result.addAll(parentScope.getVariables());
        }
        result.addAll(this.variablesMap.values());
        return result;
    }

    /** Flat map of name → value for all visible variables. */
    public Map<String, Object> getVariableValueMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (parentScope != null) {
            result.putAll(parentScope.getVariableValueMap());
        }
        this.variablesMap.forEach((k, v) -> result.put(k, v.getValue()));
        return result;
    }

    /** Scope names from innermost to outermost. */
    public List<String> getScopeNamesHierarchy() {
        List<String> result = new ArrayList<>();
        result.add(scopeName);
        if (parentScope != null) {
            result.addAll(parentScope.getScopeNamesHierarchy());
        }
        return result;
    }
}
