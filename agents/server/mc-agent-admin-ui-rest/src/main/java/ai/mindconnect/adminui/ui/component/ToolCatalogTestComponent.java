package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.JsonSchemaSample;
import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Body of the "Test tool" modal for the top-level tool catalog — the same
 * shape as {@link ToolTestComponent}, but identified by tool <em>name</em>
 * (no owning agent). Dispatches to {@code /admin/api/tools/{name}/test}.
 */
public final class ToolCatalogTestComponent implements UiComponent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String toolName;
    private final Namespace namespace;
    private final ToolRegistry toolRegistry;
    private final String previousJson;
    private final ToolTestService.Result result;

    public ToolCatalogTestComponent(String toolName,
                                    Namespace namespace,
                                    ToolRegistry toolRegistry,
                                    String previousJson,
                                    ToolTestService.Result result) {
        this.toolName = toolName;
        this.namespace = namespace;
        this.toolRegistry = toolRegistry;
        this.previousJson = previousJson;
        this.result = result;
    }

    @Override
    public String id() { return "tool-cat-test-" + toolName; }

    public String title() { return "Test " + toolName; }

    @Override
    public UiNode render() {
        Map<String, Object> schemaMap = resolveSchema();
        String schema = prettySchema(schemaMap);
        // Pre-fill with an example derived from the schema, so the admin starts
        // from a skeleton with the right fields rather than an empty object.
        String initialJson = previousJson != null && !previousJson.isBlank()
                ? previousJson
                : JsonSchemaSample.example(schemaMap);

        var form = UiForm.of(id(), null);
        if (schema != null) {
            form.field(UiField.textarea("inputSchema", "Input Schema (read-only)", schema)
                    .hint("Use this as a reference when filling in the JSON arguments below."));
        }
        form.field(UiField.textarea("arguments", "Arguments (JSON)", initialJson)
                .asEditable().asRequired()
                .hint("A JSON object passed to Tool.execute(Map). Empty object = no arguments."))
            .action(UiAction.primary("send", "Send").icon("send")
                    .dispatch("POST", "/admin/api/tools/" + toolName + "/test", id()))
            // Close only removes the overlay (a tiny remove-patch) — never a
            // page reload, so the page state behind the dialog survives.
            .action(UiAction.secondary("close", "Close").icon("close")
                    .dispatch("POST", "/admin/api/tools/test-dialog/close"));

        var stack = UiStack.of(id() + "-stack").child(form);
        if (result != null) stack.child(renderResult());
        return stack;
    }

    /** Resolves the tool's parameter schema, or null if the tool can't resolve. */
    private Map<String, Object> resolveSchema() {
        var resolved = toolRegistry.resolve(
                AgentTool.of(new UUID(0, 0), toolName), namespace, null, null);
        return resolved.map(t -> t.parametersSchema()).orElse(null);
    }

    /** Pretty-prints a schema map for the read-only display, or null. */
    private static String prettySchema(Map<String, Object> schemaMap) {
        if (schemaMap == null) return null;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schemaMap);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private UiNode renderResult() {
        if (result.ok()) {
            String meta = "✓ OK · " + result.durationMs() + " ms";
            String body = result.text() == null || result.text().isEmpty()
                    ? "(empty result)"
                    : result.text();
            return UiStack.of(id() + "-ok")
                    .<UiStack>withCssClass("llm-test-result llm-test-result--ok")
                    .child(UiText.of(id() + "-meta", meta).<UiText>withCssClass("llm-test-meta"))
                    .child(UiText.of(id() + "-body", body).<UiText>withCssClass("llm-test-body"));
        }
        String meta = "✗ Failed · " + result.durationMs() + " ms";
        return UiStack.of(id() + "-err")
                .<UiStack>withCssClass("llm-test-result llm-test-result--err")
                .child(UiText.of(id() + "-meta", meta).<UiText>withCssClass("llm-test-meta"))
                .child(UiText.of(id() + "-body", result.errorMessage()).<UiText>withCssClass("llm-test-body"));
    }
}
