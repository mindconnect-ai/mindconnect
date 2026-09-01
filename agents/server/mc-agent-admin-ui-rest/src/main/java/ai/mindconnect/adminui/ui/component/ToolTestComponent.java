package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.adminui.ui.controller.AgentUiController;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.adminui.ui.controller.ToolCatalogUiController;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Body of the "Test tool" modal dialog. Mirrors {@link LlmConfigTestComponent}
 * in shape:
 *
 * <ol>
 *   <li>read-only Input Schema (so the admin knows what to type),</li>
 *   <li>textarea for the JSON {@code arguments} payload,</li>
 *   <li>Send / Close actions,</li>
 *   <li>result block underneath once a test has run (green for OK, red
 *       for failure — same CSS classes as the LLM-config dialog).</li>
 * </ol>
 */
public final class ToolTestComponent implements UiComponent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentDefinition agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;
    private final String previousJson;
    private final ToolTestService.Result result;

    public ToolTestComponent(AgentDefinition agent,
                              AgentTool tool,
                              ToolRegistry toolRegistry,
                              String previousJson,
                              ToolTestService.Result result) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
        this.previousJson = previousJson;
        this.result = result;
    }

    @Override
    public String id() { return "tool-test-" + tool.id(); }

    public String title() { return "Test " + tool.name(); }

    @Override
    public UiNode render() {
        String schema = renderSchema();
        String initialJson = previousJson == null || previousJson.isBlank()
                ? "{}\n"
                : previousJson;

        var form = UiForm.of(id(), null);
        if (schema != null) {
            form.field(UiField.textarea("inputSchema", "Input Schema (read-only)", schema)
                    .hint("Use this as a reference when filling in the JSON arguments below."));
        }
        form.field(UiField.textarea("arguments", "Arguments (JSON)", initialJson)
                .asEditable().asRequired()
                .hint("A JSON object passed to Tool.execute(Map). Empty object = no arguments."))
            .action(UiAction.primary("send", "Send").icon("send")
                    .onClick(trigger(on(AgentUiController.class).runToolTest(agent.id(), tool.id(), null),
                            id())))
            // Close only removes the overlay (a tiny remove-patch) — never a
            // page reload, so the page state behind the dialog survives.
            .action(UiAction.secondary("close", "Close").icon("close")
                    .onClick(trigger(on(ToolCatalogUiController.class).closeTestDialog())));

        var stack = UiStack.of(id() + "-stack").child(form);
        if (result != null) stack.child(renderResult());
        return stack;
    }

    /**
     * Pretty-prints the tool's input-schema for display. Returns
     * {@code null} when the registry can't resolve the tool (typically
     * because a factory is missing) — the dialog still works, the admin
     * just won't see the schema box.
     */
    private String renderSchema() {
        var resolved = toolRegistry.resolve(tool, agent.namespace(), null, null);
        if (resolved.isEmpty()) return null;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(resolved.get().parametersSchema());
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
