package ai.mindconnect.workflow.dsl.puml;

import ai.mindconnect.workflow.domain.*;

import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link WorkflowData} back to PlantUML activity diagram syntax.
 *
 * <p>Together with {@link PumlWorkflowParser} this class provides a round-trip:
 * <pre>
 *   WorkflowData  →  PumlWorkflowSerializer  →  String (puml)
 *   String (puml) →  PumlWorkflowParser       →  WorkflowData
 * </pre>
 *
 * <h2>Multiline values</h2>
 * When a {@code code} or {@code body} property contains newlines or semicolons,
 * it is emitted as a {@code note right ... end note} block attached to the step
 * rather than as an inline property — keeping the action block itself valid
 * PlantUML and readable.
 *
 * <h2>Input / output notation</h2>
 * Workflow params use {@code :input -> param1, param2;} before {@code start}.
 * The result variable uses {@code :output <- varName;} after {@code stop}.
 * ForEach loop config uses {@code :for -> items as item index i parallel;}.
 */
public class PumlWorkflowSerializer {

    private static final String INDENT = "  ";

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Serializes the workflow to a PlantUML string. */
    public String serialize(WorkflowData wf) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml");
        if (wf.getName() != null && !wf.getName().isBlank()) {
            sb.append(' ').append(wf.getName());
        }
        sb.append('\n');

        // Input params — before start
        if (!wf.paramNames().isEmpty()) {
            sb.append(":input -> ").append(String.join(", ", wf.paramNames())).append(";\n");
        }
        if (wf.getWorkflowType() != null) {
            sb.append("' workflowType: ").append(wf.getWorkflowType()).append('\n');
        }

        sb.append("start\n\n");
        serializeSteps(wf.getSteps(), sb, "");
        sb.append("\nstop\n");

        // Output result — after stop
        if (wf.getResultFrom() != null) {
            sb.append(":output <- ").append(wf.getResultFrom()).append(";\n");
        }

        // Sub-workflows
        for (WorkflowData sub : wf.getSubWorkflows()) {
            sb.append('\n');
            serializeSubWorkflow(sub, sb);
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Step serialization
    // -----------------------------------------------------------------------

    private void serializeSteps(List<StepData> steps, StringBuilder sb, String indent) {
        for (StepData step : steps) {
            serializeStep(step, sb, indent);
            sb.append('\n');
        }
    }

    private void serializeStep(StepData step, StringBuilder sb, String indent) {
        switch (step) {
            case AssignVariablesData assign -> serializeAssign(assign, sb, indent);
            case IfData ifData -> serializeIf(ifData, sb, indent);
            case ForEachData forEach -> serializeForEach(forEach, sb, indent);
            case HaltData halt -> serializeHalt(halt, sb, indent);
            case JumpToData jump -> serializeJump(jump, sb, indent);
            case CallWorkflowData call -> serializeCall(call, sb, indent);
            case BlockData block -> serializeBlock(block, sb, indent);
            default -> serializeGeneric(step, sb, indent);
        }
    }

    private void serializeAssign(AssignVariablesData assign, StringBuilder sb, String indent) {
        sb.append(indent).append(":assign ").append(assign.getName()).append('\n');
        for (VariableAssignment va : assign.getVariableAssignments()) {
            sb.append(indent).append(INDENT).append(va.getVarName())
              .append(" = ").append(va.getExpressionOrVarName()).append('\n');
        }
        if (assign.getAssignResultToVar() != null) {
            sb.append(indent).append(INDENT).append("assign-result -> ")
              .append(assign.getAssignResultToVar()).append('\n');
        }
        sb.append(indent).append(";");
    }

    private void serializeIf(IfData ifData, StringBuilder sb, String indent) {
        IfData.Condition[] conditions = ifData.getConditions();
        if (conditions == null || conditions.length == 0) return;

        IfData.Condition first = conditions[0];
        sb.append(indent).append("if (").append(first.getCondition()).append(") then (yes)\n");
        if (first.getThenBlock() != null) {
            serializeSteps(first.getThenBlock().getSteps(), sb, indent + INDENT);
        }

        // Additional conditions as elseif (flattened to else + nested if)
        for (int i = 1; i < conditions.length; i++) {
            IfData.Condition cond = conditions[i];
            sb.append(indent).append("else (no)\n");
            sb.append(indent + INDENT).append("if (").append(cond.getCondition()).append(") then (yes)\n");
            if (cond.getThenBlock() != null) {
                serializeSteps(cond.getThenBlock().getSteps(), sb, indent + INDENT + INDENT);
            }
        }

        if (ifData.getElseBlock() != null && !ifData.getElseBlock().getSteps().isEmpty()) {
            sb.append(indent).append("else (no)\n");
            serializeSteps(ifData.getElseBlock().getSteps(), sb, indent + INDENT);
        }

        for (int i = 1; i < conditions.length; i++) {
            sb.append(indent + INDENT).append("endif\n");
        }
        sb.append(indent).append("endif");
    }

    private void serializeForEach(ForEachData forEach, StringBuilder sb, String indent) {
        // :for -> items as item index idx parallel;
        if (forEach.getLoopOver() != null) {
            sb.append(indent).append(":for -> ").append(forEach.getLoopOver());
            if (forEach.getRunVar() != null) sb.append(" as ").append(forEach.getRunVar());
            if (forEach.getIndexVar() != null) sb.append(" index ").append(forEach.getIndexVar());
            if (forEach.isParallel()) sb.append(" parallel");
            sb.append(";\n");
        }
        sb.append(indent).append("fork\n");
        serializeSteps(forEach.getSteps(), sb, indent + INDENT);
        sb.append(indent).append("end fork");
        if (forEach.getAssignResultToVar() != null) {
            sb.append('\n').append(indent)
              .append(":output <- ").append(forEach.getAssignResultToVar()).append(";");
        }
    }

    private void serializeHalt(HaltData halt, StringBuilder sb, String indent) {
        sb.append(indent).append(":halt ").append(halt.getName()).append('\n');
        if (halt.getCondition() != null) {
            sb.append(indent).append(INDENT).append("condition = ").append(halt.getCondition()).append('\n');
        }
        if (halt.getNext() != null) {
            sb.append(indent).append(INDENT).append("next = ").append(halt.getNext()).append('\n');
        }
        if (halt.getReturnResultExpression() != null) {
            sb.append(indent).append(INDENT)
              .append("returnResultExpression = ").append(halt.getReturnResultExpression()).append('\n');
        }
        if (!halt.isReturnResult()) {
            sb.append(indent).append(INDENT).append("returnResult = false\n");
        }
        sb.append(indent).append(";");
    }

    private void serializeJump(JumpToData jump, StringBuilder sb, String indent) {
        sb.append(indent).append(":jump ").append(jump.getName()).append('\n');
        if (jump.getJumpTo() != null) {
            sb.append(indent).append(INDENT).append("to = ").append(jump.getJumpTo()).append('\n');
        }
        sb.append(indent).append(";");
    }

    private void serializeCall(CallWorkflowData call, StringBuilder sb, String indent) {
        sb.append(indent).append(":call ").append(call.getName()).append('\n');
        if (call.getWorkflow() != null) {
            sb.append(indent).append(INDENT).append("workflow = ").append(call.getWorkflow()).append('\n');
        }
        for (Map.Entry<String, String> e : call.getAssignParams().entrySet()) {
            sb.append(indent).append(INDENT).append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        if (call.getAssignResultToVar() != null) {
            sb.append(indent).append(INDENT).append("assign-result -> ").append(call.getAssignResultToVar()).append('\n');
        }
        sb.append(indent).append(";");
    }

    private void serializeBlock(BlockData block, StringBuilder sb, String indent) {
        serializeSteps(block.getSteps(), sb, indent);
    }

    /**
     * Generic serializer for step types unknown to the core (e.g. CodeData, HttpCallData).
     * Uses reflection to read fields via known getter names, emitting inline properties
     * for single-line values and a {@code note right} block for multiline values.
     */
    private void serializeGeneric(StepData step, StringBuilder sb, String indent) {
        String type = step.getType();
        String name = step.getName();

        // Collect properties via the step's type-specific accessor map
        Map<String, String> inline = new java.util.LinkedHashMap<>();
        Map<String, String> multiline = new java.util.LinkedHashMap<>();
        collectStepProps(step, inline, multiline);

        if (inline.isEmpty() && multiline.isEmpty()) {
            // No properties known — emit a comment
            sb.append(indent).append("' [").append(step.getClass().getSimpleName())
              .append(" name=").append(name).append(']');
            return;
        }

        // Action header
        sb.append(indent).append(":").append(type).append(" ").append(name).append('\n');
        for (Map.Entry<String, String> e : inline.entrySet()) {
            sb.append(indent).append(INDENT).append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        if (step.getAssignResultToVar() != null) {
            sb.append(indent).append(INDENT).append("assign-result -> ").append(step.getAssignResultToVar()).append('\n');
        }
        sb.append(indent).append(";");

        // Trailing note for multiline values
        if (!multiline.isEmpty()) {
            sb.append('\n').append(indent).append("note right\n");
            for (Map.Entry<String, String> e : multiline.entrySet()) {
                // Emit as "key:\n  line1\n  line2" style
                sb.append(indent).append(INDENT).append(e.getKey()).append(":\n");
                for (String line : e.getValue().split("\n", -1)) {
                    sb.append(indent).append(INDENT).append(INDENT).append(line).append('\n');
                }
            }
            sb.append(indent).append("end note");
        }
    }

    /**
     * Collects the serializable properties of a step using reflection on known optional types.
     * Properties with single-line values go into {@code inline}; multiline values go into
     * {@code multiline}.
     */
    private void collectStepProps(StepData step, Map<String, String> inline, Map<String, String> multiline) {
        try {
            Class<?> codeDataClass = Class.forName("ai.mindconnect.workflow.domain.CodeData");
            if (codeDataClass.isInstance(step)) {
                putProp("language", invoke(step, "getLanguage"), inline, multiline);
                putProp("code", invoke(step, "getCode"), inline, multiline);
                putBoolProp("wrapInFunction", (Boolean) codeDataClass.getMethod("isWrapInFunction").invoke(step),
                        false, inline);
                putBoolProp("injectVariables", (Boolean) codeDataClass.getMethod("isInjectVariables").invoke(step),
                        true, inline);
                putBoolProp("exportVariables", (Boolean) codeDataClass.getMethod("isExportVariables").invoke(step),
                        true, inline);
                return;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            Class<?> httpDataClass = Class.forName("ai.mindconnect.workflow.domain.HttpCallData");
            if (httpDataClass.isInstance(step)) {
                putProp("url", invoke(step, "getUrl"), inline, multiline);
                putProp("method", invoke(step, "getMethod"), inline, multiline);
                putProp("contentType", invoke(step, "getContentType"), inline, multiline);
                putProp("body", invoke(step, "getBody"), inline, multiline);
                putBoolProp("failOnError", (Boolean) httpDataClass.getMethod("isFailOnError").invoke(step),
                        true, inline);
                putIntProp("timeoutMs", (Integer) httpDataClass.getMethod("getTimeoutMs").invoke(step),
                        0, inline);
                putProp("statusCodeVar", invoke(step, "getStatusCodeVar"), inline, multiline);
                putProp("responseHeadersVar", invoke(step, "getResponseHeadersVar"), inline, multiline);
                // Headers
                @SuppressWarnings("unchecked")
                List<VariableAssignment> headers =
                        (List<VariableAssignment>) httpDataClass.getMethod("getHeaders").invoke(step);
                for (VariableAssignment h : headers) {
                    inline.put("header." + h.getVarName(), h.getExpressionOrVarName());
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String invoke(Object obj, String methodName) {
        try {
            return (String) obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /** Adds a string property to the appropriate map based on whether it's multiline. */
    private void putProp(String key, String value, Map<String, String> inline, Map<String, String> multiline) {
        if (value == null) return;
        if (value.contains("\n") || value.contains(";")) {
            multiline.put(key, value);
        } else {
            inline.put(key, value);
        }
    }

    /** Adds a boolean property only when it differs from the default value. */
    private void putBoolProp(String key, Boolean value, boolean defaultValue, Map<String, String> inline) {
        if (value == null || value == defaultValue) return;
        inline.put(key, value.toString());
    }

    /** Adds an integer property only when it differs from the default value. */
    private void putIntProp(String key, Integer value, int defaultValue, Map<String, String> inline) {
        if (value == null || value == defaultValue) return;
        inline.put(key, value.toString());
    }

    // -----------------------------------------------------------------------
    // Sub-workflow
    // -----------------------------------------------------------------------

    private void serializeSubWorkflow(WorkflowData sub, StringBuilder sb) {
        sb.append("partition ").append(sub.getName()).append(" {\n");
        if (!sub.paramNames().isEmpty()) {
            sb.append(INDENT).append(":input -> ").append(String.join(", ", sub.paramNames())).append(";\n");
        }
        serializeSteps(sub.getSteps(), sb, INDENT);
        if (sub.getResultFrom() != null) {
            sb.append(INDENT).append(":output <- ").append(sub.getResultFrom()).append(";\n");
        }
        sb.append("}\n");
    }
}
