package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.JumpToData;
import ai.mindconnect.workflow.domain.StepData;

import java.util.List;

/**
 * Builds a typed {@link UiForm} for editing one step, choosing inputs per step
 * type (text / number / boolean / select). Every form carries the two base
 * fields ({@code name}, {@code assignResultToVar}); type-specific fields are
 * added on top. The form posts back to the admin controller, which maps the
 * submitted values onto the step.
 */
public class StepFormBuilder {

    /** Engine names as registered on the {@code WorkflowContext}. {@code mini}
     *  is always available — {@code DefaultWorkflowContextFactory} registers it
     *  unconditionally; the others need their {@code mc-workflow-code-*} module
     *  on the classpath. */
    private static final List<UiField.Option> LANGUAGES = List.of(
            UiField.Option.of("mini", "MiniScript (built-in)"),
            UiField.Option.of("javascript", "JavaScript"),
            UiField.Option.of("groovy", "Groovy"),
            UiField.Option.of("beanshell", "BeanShell"),
            UiField.Option.of("jython", "Jython"));

    private static final List<UiField.Option> HTTP_METHODS = List.of(
            UiField.Option.of("GET", "GET"),
            UiField.Option.of("POST", "POST"),
            UiField.Option.of("PUT", "PUT"),
            UiField.Option.of("DELETE", "DELETE"),
            UiField.Option.of("PATCH", "PATCH"));

    /**
     * Build the edit form for {@code step}; {@code saveUrl} receives the POST.
     * {@code jsonUrl} opens the raw-JSON editor for the same step — the escape
     * hatch for fields this typed form doesn't model.
     */
    public UiForm build(StepData step, String saveUrl, String jsonUrl) {
        String formId = "step-form";
        UiForm form = UiForm.of(formId, "Edit " + step.getType() + " step");

        // Common fields
        form.field(UiField.text("name", "Name", step.getName()).asEditable().asRequired());
        form.field(UiField.text("assignResultToVar", "Assign result to var",
                step.getAssignResultToVar()).asEditable());

        // Type-specific fields
        addTypeFields(form, step);

        form.action(UiAction.primary("save", "Save").icon("save").dispatch("POST", saveUrl, formId));
        form.action(UiAction.secondary("json", "Edit as JSON").icon("code")
                .onClick(UiTrigger.api("GET", jsonUrl)));
        form.action(UiAction.secondary("cancel", "Cancel").icon("cancel").onClick(UiTrigger.go(backUrl(saveUrl))));
        return form;
    }

    /**
     * Build the raw-JSON editor for one step. Saving replaces the whole step
     * (children included), so the {@code @class} discriminator in the JSON is
     * what decides the resulting step type.
     *
     * @param json the text to show — the step's current JSON, or, after a parse
     *             failure, the text the user submitted, so their edit survives
     */
    public UiForm buildJson(StepData step, String json, String jsonSaveUrl, String editUrl) {
        String formId = "step-json-form";
        UiForm form = UiForm.of(formId, step.getType() + " step — JSON");
        form.field(UiField.textarea("json", "Step JSON", json).asEditable().asRequired()
                .hint("The whole step, children included. \"@class\" selects the step type."));
        form.action(UiAction.primary("save", "Save").icon("save").dispatch("POST", jsonSaveUrl, formId));
        form.action(UiAction.secondary("form", "Back to form").icon("back")
                .onClick(UiTrigger.api("GET", editUrl)));
        return form;
    }

    private void addTypeFields(UiForm form, StepData step) {
        // Contributed step types build their own fields (base fields are already on the form).
        for (AdminStepPlugin plugin : StepMapper.PLUGINS) {
            if (plugin.buildForm(step, form)) {
                return;
            }
        }
        if (step instanceof CodeData code) {
            form.field(UiField.select("language", "Language", code.getLanguage(), LANGUAGES).asEditable());
            form.field(UiField.textarea("code", "Code", code.getCode()).asEditable());
            return;
        }
        if (step instanceof HttpCallData http) {
            form.field(UiField.text("url", "URL", http.getUrl()).asEditable().asRequired());
            form.field(UiField.select("method", "Method", http.getMethod(), HTTP_METHODS).asEditable());
            form.field(UiField.textarea("body", "Body", http.getBody()).asEditable());
            form.field(UiField.text("contentType", "Content type", http.getContentType()).asEditable());
            form.field(UiField.bool("failOnError", "Fail on error", http.isFailOnError()).asEditable());
            form.field(UiField.text("statusCodeVar", "Status code var", http.getStatusCodeVar()).asEditable());
            return;
        }
        if (step instanceof ForEachData fe) {
            form.field(UiField.text("loopOver", "Loop over", fe.getLoopOver()).asEditable().asRequired());
            form.field(UiField.text("runVar", "Item var", fe.getRunVar()).asEditable());
            form.field(UiField.text("indexVar", "Index var", fe.getIndexVar()).asEditable());
            form.field(UiField.bool("parallel", "Parallel", fe.isParallel()).asEditable());
            form.field(UiField.bool("joinResults", "Join results", fe.isJoinResults()).asEditable());
            form.field(UiField.text("joinDelimiter", "Join delimiter", fe.getJoinDelimiter()).asEditable());
            return;
        }
        if (step instanceof CallWorkflowData call) {
            form.field(UiField.text("workflow", "Workflow", call.getWorkflow()).asEditable().asRequired());
            return;
        }
        if (step instanceof JumpToData jump) {
            form.field(UiField.text("jumpTo", "Jump to step", jump.getJumpTo()).asEditable().asRequired());
            return;
        }
        if (step instanceof HaltData halt) {
            form.field(UiField.text("condition", "Condition", halt.getCondition()).asEditable()
                    .hint("Only halts when true — e.g. \"mini: amount > 1000\"."));
            form.field(UiField.text("resumeParams", "Waits for",
                            String.join(", ", halt.getResumeParams().getProperties().keySet())).asEditable()
                    .hint("Comma-separated inputs the resume should bring back (a verdict, a "
                          + "user's reply) — asked for on Continue. Names here become text "
                          + "fields; for enums, defaults or a multiline box, type the "
                          + "resumeParams object in 'Edit as JSON'."));
            form.field(UiField.bool("returnResult", "Return result", halt.isReturnResult()).asEditable());
            form.field(UiField.text("returnResultExpression", "Result expression",
                    halt.getReturnResultExpression()).asEditable());
            form.field(UiField.text("next", "Resume at step", halt.getNext()).asEditable());
            return;
        }
        if (step instanceof AssignVariablesData) {
            // Variable assignments are a list of pairs; edit them as raw JSON for now.
            // (A dedicated repeating editor is a follow-up.)
            form.field(UiField.textarea("variableAssignmentsJson",
                    "Variable assignments (JSON)", null).asEditable()
                    .hint("[ { \"varName\": \"x\", \"expressionOrVarName\": \"1\" } ]"));
        }
        // Block and other pure-container steps have no extra scalar fields here.
    }

    private static String backUrl(String saveUrl) {
        // saveUrl is .../{wf}/step/{path}/save -> back to the workflow view.
        int idx = saveUrl.indexOf("/step/");
        return idx > 0 ? saveUrl.substring(0, idx) : saveUrl;
    }
}
