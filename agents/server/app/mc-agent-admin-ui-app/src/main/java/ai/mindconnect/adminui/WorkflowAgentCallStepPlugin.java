package ai.mindconnect.adminui;

import ai.mindconnect.agent.tools.workflow.step.AgentCallData;
import ai.mindconnect.agent.tools.workflow.step.ToolCallData;
import ai.mindconnect.agent.tools.workflow.step.ToolInvoker;
import ai.mindconnect.agent.tools.workflow.step.ToolInvokers;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.workflow.admin.ui.AdminStepPlugin;
import ai.mindconnect.workflow.domain.StepData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Makes the agent-call and tool-call steps authorable in the embedded workflow
 * admin: picker entries, edit-form fields and the mapping of submitted values
 * back onto the steps. Registered via ServiceLoader — this app is the place
 * that has both the workflow admin and the agent runtime on the classpath.
 */
public final class WorkflowAgentCallStepPlugin implements AdminStepPlugin {

    @Override
    public List<UiField.Option> typeOptions() {
        return List.of(
                UiField.Option.of("agentcall", "Agent call"),
                UiField.Option.of("toolcall", "Tool call"));
    }

    @Override
    public StepData create(String type) {
        return switch (type) {
            case "agentcall" -> new AgentCallData();
            case "toolcall" -> new ToolCallData();
            default -> null;
        };
    }

    @Override
    public boolean buildForm(StepData step, UiForm form) {
        if (step instanceof AgentCallData call) {
            form.field(UiField.text("agent", "Agent", call.getAgent())
                    .asEditable().asRequired()
                    .hint("Name of the agent definition to call (see the Agents list)"));
            form.field(UiField.textarea("message", "Message", call.getMessage())
                    .asEditable().asRequired()
                    .hint("The message sent to the agent — ${var} references resolve against the workflow scope"));
            return true;
        }
        if (step instanceof ToolCallData call) {
            form.field(toolField(call));
            form.field(UiField.textarea("arguments", "Arguments (JSON)", call.getArguments())
                    .asEditable()
                    .hint("JSON object with the tool's arguments — ${var} references resolve "
                          + "against the workflow scope, e.g. {\"query\": \"${topic}\"}"));
            return true;
        }
        return false;
    }

    /** A live dropdown of registered tools when the runtime is up; a plain text field otherwise. */
    private static UiField toolField(ToolCallData call) {
        ToolInvoker invoker = ToolInvokers.getOrNull();
        if (invoker == null || invoker.knownToolNames().isEmpty()) {
            return UiField.text("tool", "Tool", call.getTool())
                    .asEditable().asRequired()
                    .hint("Registry name of the tool (see the Tools catalog)");
        }
        List<UiField.Option> options = new ArrayList<>();
        for (String name : new TreeSet<>(invoker.knownToolNames())) {
            options.add(UiField.Option.of(name, name));
        }
        return UiField.select("tool", "Tool", call.getTool(), options)
                .asEditable().asRequired();
    }

    @Override
    public boolean apply(StepData step, Map<String, Object> form) {
        if (step instanceof AgentCallData call) {
            if (form.containsKey("agent")) {
                call.setAgent(asString(form.get("agent")));
            }
            if (form.containsKey("message")) {
                call.setMessage(asString(form.get("message")));
            }
            return true;
        }
        if (step instanceof ToolCallData call) {
            if (form.containsKey("tool")) {
                call.setTool(asString(form.get("tool")));
            }
            if (form.containsKey("arguments")) {
                call.setArguments(asString(form.get("arguments")));
            }
            return true;
        }
        return false;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
