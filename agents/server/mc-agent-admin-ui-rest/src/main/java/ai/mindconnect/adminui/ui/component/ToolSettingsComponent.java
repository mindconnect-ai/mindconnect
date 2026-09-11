package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.tool.ToolSettings;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Body of the "Settings" modal for one tool: switch it off, and say what it
 * and its parameters should tell the model.
 *
 * <p>The source's own text sits beside every field, read-only. That is what
 * makes replacing safe to offer at all — an operator sees what they are
 * replacing, can copy it, and notices when the source has moved on
 * (concept 22 §4.1, §7).
 */
public final class ToolSettingsComponent implements UiComponent {

    /** The tool this dialog edits. */
    private final String toolName;
    private final ToolSettings settings;
    /** What the tool says about itself, before any override. */
    private final String sourceDescription;
    /** parameter name → the source's description (may be blank). */
    private final Map<String, String> sourceParameters;
    private final String message;

    public ToolSettingsComponent(String toolName, ToolSettings settings,
                                 String sourceDescription, Map<String, String> sourceParameters,
                                 String message) {
        this.toolName = toolName;
        this.settings = settings == null ? ToolSettings.none() : settings;
        this.sourceDescription = sourceDescription;
        this.sourceParameters = sourceParameters == null ? Map.of() : sourceParameters;
        this.message = message;
    }

    @Override
    public String id() {
        return "tool-settings-" + toolName;
    }

    public String title() {
        return "Settings for " + toolName;
    }

    @Override
    public UiNode render() {
        UiForm form = UiForm.of(id(), null);

        form.field(UiField.bool("enabled", "Available to agents", settings.enabledOrDefault())
                .asEditable()
                .hint("Off removes it from this catalog and from every agent that binds it. "
                        + "The agent keeps working, minus this one capability."));

        if (sourceDescription != null && !sourceDescription.isBlank()) {
            form.field(UiField.textarea("sourceDescription", "What the tool says about itself",
                            sourceDescription)
                    .hint("Read-only. Copy from here if you want to keep part of it — an override "
                            + "replaces this text entirely."));
        }
        form.field(UiField.textarea("description", "Description for the model",
                        settings.description())
                .asEditable()
                .hint("Empty means: use the text above. This is what the model reads when it "
                        + "decides whether to call the tool."));

        // Every parameter with something to say: those the source offers,
        // plus those an operator has already written for. The second half
        // earns its place twice — a stored text stays visible (and therefore
        // removable) when the source has dropped the parameter, and a save
        // made while the source cannot be resolved no longer silently drops
        // what the form could not show. Saving replaces the whole map, so
        // whatever is not rendered here is lost.
        Set<String> parameters = new LinkedHashSet<>(sourceParameters.keySet());
        parameters.addAll(settings.parameterDescriptions().keySet());
        parameters.forEach(parameter -> {
            String current = settings.parameterDescriptions().get(parameter);
            String sourceText = sourceParameters.get(parameter);
            form.field(UiField.textarea(parameterField(parameter),
                            "Parameter \"" + parameter + "\"", current)
                    .asEditable()
                    .hint(hintFor(parameter, sourceText)));
        });

        form.action(UiAction.primary("save", "Save").icon("save")
                        .dispatch("POST", "/admin/api/tools/" + toolName + "/settings", id()))
                .action(UiAction.danger("reset", "Reset to defaults").icon("refresh")
                        .confirm("Drop all settings for '" + toolName + "'?")
                        .dispatch("DELETE", "/admin/api/tools/" + toolName + "/settings"))
                .action(UiAction.secondary("close", "Close").icon("close")
                        .dispatch("POST", "/admin/api/tools/settings-dialog/close"));

        UiStack stack = UiStack.of(id() + "-stack").child(form);
        if (message != null) {
            stack.child(UiStack.of(id() + "-msg")
                    .<UiStack>withCssClass("llm-test-result llm-test-result--ok")
                    .child(UiText.of(id() + "-msg-text", message).<UiText>withCssClass("llm-test-meta")));
        }
        return stack;
    }

    /**
     * What emptying this field would mean. Three cases, and the third is the
     * one worth naming: an override for a parameter the tool no longer has
     * would otherwise sit in the store forever, doing nothing and explaining
     * nothing.
     */
    private String hintFor(String parameter, String sourceText) {
        if (!sourceParameters.containsKey(parameter)) {
            return "The tool has no such parameter (any more) — this override does nothing. "
                    + "Empty the field to drop it.";
        }
        return sourceText == null || sourceText.isBlank()
                ? "The tool says nothing about this parameter. Empty means it stays that way."
                : "Empty means: \"" + sourceText + "\"";
    }

    /** Parameter fields travel under a prefix so the controller can tell them apart. */
    public static String parameterField(String parameter) {
        return "param." + parameter;
    }

    /** The parameter descriptions out of a submitted form, by parameter name. */
    public static Map<String, String> parametersFrom(Map<String, Object> body) {
        Map<String, String> parameters = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (key.startsWith("param.") && value != null && !value.toString().isBlank()) {
                parameters.put(key.substring("param.".length()), value.toString().trim());
            }
        });
        return parameters;
    }
}
