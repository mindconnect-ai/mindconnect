package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.schema.Schema;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.workflow.step.form.FormStepData;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.JumpToData;
import ai.mindconnect.workflow.domain.StepData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Creates new steps by type and applies submitted form values back onto an
 * existing step. The form field names match the inputs the
 * {@link StepFormBuilder} emits.
 */
public final class StepMapper {

    private StepMapper() {}

    /** Step types contributed by other modules (ServiceLoader, loaded once). */
    static final List<AdminStepPlugin> PLUGINS = java.util.ServiceLoader
            .load(AdminStepPlugin.class).stream()
            .map(java.util.ServiceLoader.Provider::get)
            .toList();

    /** Type options for the "add step" picker (value = type discriminator). */
    public static List<UiField.Option> typeOptions() {
        List<UiField.Option> options = new ArrayList<>(List.of(
                UiField.Option.of("assignvariables", "Assign variables"),
                UiField.Option.of("block", "Block"),
                UiField.Option.of("if", "If"),
                UiField.Option.of("foreach", "For each"),
                UiField.Option.of("code", "Code"),
                UiField.Option.of("httpcall", "HTTP call"),
                UiField.Option.of("callworkflow", "Call workflow"),
                UiField.Option.of("jumpto", "Jump to"),
                UiField.Option.of("halt", "Halt"),
                UiField.Option.of("formstep", "Form (halt + shows a form)")));
        for (AdminStepPlugin plugin : PLUGINS) {
            options.addAll(plugin.typeOptions());
        }
        return options;
    }

    /** A fresh, empty step for the given type discriminator. */
    public static StepData create(String type) {
        return switch (type) {
            case "assignvariables" -> new AssignVariablesData();
            case "block"           -> new BlockData();
            case "if"              -> new IfData();
            case "foreach"         -> new ForEachData();
            case "code"            -> new CodeData();
            case "httpcall"        -> new HttpCallData();
            case "callworkflow"    -> new CallWorkflowData();
            case "jumpto"          -> new JumpToData();
            case "halt"            -> new HaltData();
            case "formstep"        -> newFormStep();
            default -> {
                for (AdminStepPlugin plugin : PLUGINS) {
                    StepData step = plugin.create(type);
                    if (step != null) yield step;
                }
                throw new IllegalArgumentException("Unknown step type: " + type);
            }
        };
    }

    /**
     * A form step with a one-field starter form, so it runs (and halts with
     * something to show) straight away. Authors reshape the form via the step's
     * "Edit as JSON" dialog — the starter doubles as the template to edit.
     */
    private static FormStepData newFormStep() {
        FormStepData step = new FormStepData();
        UiForm starter = UiForm.of("form", "Your input");
        starter.field(UiField.text("answer", "Answer", null).asEditable().asRequired());
        step.setForm(starter);
        return step;
    }

    /** Apply the submitted form values onto {@code step}. */
    public static void apply(StepData step, Map<String, Object> f) {
        if (f.containsKey("name")) step.setName(str(f, "name"));
        if (f.containsKey("assignResultToVar")) step.setAssignResultToVar(str(f, "assignResultToVar"));

        if (step instanceof CodeData code) {
            if (f.containsKey("language")) code.setLanguage(str(f, "language"));
            if (f.containsKey("code")) code.setCode(str(f, "code"));
        } else if (step instanceof HttpCallData http) {
            if (f.containsKey("url")) http.setUrl(str(f, "url"));
            if (f.containsKey("method")) http.setMethod(str(f, "method"));
            if (f.containsKey("body")) http.setBody(str(f, "body"));
            if (f.containsKey("contentType")) http.setContentType(str(f, "contentType"));
            if (f.containsKey("failOnError")) http.setFailOnError(bool(f, "failOnError"));
            if (f.containsKey("statusCodeVar")) http.setStatusCodeVar(str(f, "statusCodeVar"));
        } else if (step instanceof ForEachData fe) {
            if (f.containsKey("loopOver")) fe.setLoopOver(str(f, "loopOver"));
            if (f.containsKey("runVar")) fe.setRunVar(str(f, "runVar"));
            if (f.containsKey("indexVar")) fe.setIndexVar(str(f, "indexVar"));
            if (f.containsKey("parallel")) fe.setParallel(bool(f, "parallel"));
            if (f.containsKey("joinResults")) fe.setJoinResults(bool(f, "joinResults"));
            if (f.containsKey("joinDelimiter")) fe.setJoinDelimiter(str(f, "joinDelimiter"));
        } else if (step instanceof CallWorkflowData call) {
            if (f.containsKey("workflow")) call.setWorkflow(str(f, "workflow"));
        } else if (step instanceof JumpToData jump) {
            if (f.containsKey("jumpTo")) jump.setJumpTo(str(f, "jumpTo"));
        } else if (step instanceof HaltData halt) {
            if (f.containsKey("condition")) halt.setCondition(str(f, "condition"));
            if (f.containsKey("returnResult")) halt.setReturnResult(bool(f, "returnResult"));
            if (f.containsKey("returnResultExpression")) {
                halt.setReturnResultExpression(str(f, "returnResultExpression"));
            }
            if (f.containsKey("next")) halt.setNext(str(f, "next"));
            if (f.containsKey("resumeParams")) {
                halt.setResumeParams(mergeResumeParams(halt.getResumeParams(), str(f, "resumeParams")));
            }
        } else {
            // Contributed step types map their own fields (base fields are done above).
            for (AdminStepPlugin plugin : PLUGINS) {
                if (plugin.apply(step, f)) break;
            }
        }
    }

    private static String str(Map<String, Object> f, String key) {
        Object v = f.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * The comma-separated "waits for" field back into an object schema. Names
     * already present keep whatever richer type they were given (an enum, a
     * default) — only new names are added as plain strings, and dropped names are
     * removed. So editing the quick field never silently flattens the types
     * someone set via the JSON editor.
     */
    private static Schema mergeResumeParams(Schema existing, String raw) {
        Schema result = Schema.object();
        List<String> names = raw == null || raw.isBlank() ? List.of()
                : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String name : names) {
            Schema prop = existing != null && existing.getProperties().containsKey(name)
                    ? existing.getProperties().get(name)
                    : Schema.string();
            result.prop(name, prop);
            if (existing != null && existing.isRequired(name)) {
                result.require(name);
            }
        }
        return result;
    }

    private static boolean bool(Map<String, Object> f, String key) {
        Object v = f.get(key);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}
