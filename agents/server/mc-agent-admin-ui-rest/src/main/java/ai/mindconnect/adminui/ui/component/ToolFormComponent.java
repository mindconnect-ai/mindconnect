package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.adminui.ui.controller.AgentUiController;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Edit form for an agent's tool. Selectable {@code builtinName}
 * dropdown is populated from the {@link ToolRegistry} so both built-in
 * tools (ServiceLoader-discovered) and MCP-backed tools (registered by
 * other modules) appear. A {@code (custom name)} option lets the user
 * type an arbitrary name in the name field below — useful for tools
 * that aren't yet on the classpath.
 *
 * <p>For an existing tool the resolved input schema is rendered as a
 * readable parameters table below the form (pinned params are already
 * stripped there, so it shows exactly what the LLM will be offered);
 * for new tools it stays hidden because there's nothing to show yet.
 */
public final class ToolFormComponent implements UiComponent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentDefinition agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;

    /** Pass {@code null} for the new-tool form. */
    public ToolFormComponent(AgentDefinition agent, AgentTool tool, ToolRegistry toolRegistry) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String id() {
        return tool == null ? "tool-new-" + agent.id() : "tool-" + tool.id();
    }

    @Override
    public UiNode render() {
        boolean isNew = tool == null;

        // Inline tools (run_agent / run_agents) are handled by AgentChatService,
        // not the ToolRegistry — surface them here too so they're selectable and
        // not mislabelled "(unregistered)", and so their schema shows below.
        Map<String, ToolDefinition> inlineDefs = new LinkedHashMap<>();
        for (var def : AgentChatService.inlineToolDefinitions()) {
            inlineDefs.put(def.name(), def);
        }

        // Grouped by rubric (sorted groups, sorted names) with the group as a
        // label prefix — the registry view is live, so dynamic providers
        // (workflows) are current. If the user is editing an existing tool
        // whose name was renamed or removed from the classpath we still
        // surface it so the row stays editable.
        java.util.SortedMap<String, java.util.SortedSet<String>> byGroup = new java.util.TreeMap<>();
        toolRegistry.toolNamesByGroup().forEach((g, names) ->
                byGroup.computeIfAbsent(g, k -> new java.util.TreeSet<>()).addAll(names));
        for (String n : inlineDefs.keySet()) {
            boolean known = byGroup.values().stream().anyMatch(s -> s.contains(n));
            if (!known) byGroup.computeIfAbsent("agents", k -> new java.util.TreeSet<>()).add(n);
        }
        List<UiField.Option> builtinOptions = new ArrayList<>();
        LinkedHashSet<String> registered = new LinkedHashSet<>();
        byGroup.forEach((g, names) -> {
            String label = ToolCatalogComponent.displayGroup(g);
            for (String n : names) {
                builtinOptions.add(UiField.Option.of(n, label + " · " + n));
                registered.add(n);
            }
        });
        if (!isNew && tool.name() != null && !registered.contains(tool.name())) {
            builtinOptions.add(UiField.Option.of(tool.name(), tool.name() + " (unregistered)"));
        }
        builtinOptions.add(UiField.Option.of("custom", "(custom name)"));

        String overridesJson = "{}";
        if (!isNew && tool.overrides() != null && !tool.overrides().isEmpty()) {
            try {
                overridesJson = MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(tool.overrides());
            } catch (JsonProcessingException ignored) {}
        }

        Object schema = null;
        if (!isNew) {
            var resolved = toolRegistry.resolve(tool, agent.namespace(), null, null);
            if (resolved.isPresent()) {
                schema = resolved.get().parametersSchema();
            } else if (inlineDefs.containsKey(tool.name())) {
                // run_agent / run_agents: schema lives in AgentChatService.
                schema = inlineDefs.get(tool.name()).parametersSchema();
            }
        }

        var form = UiForm.of(id(), isNew ? "Add Tool" : "Edit Tool: " + tool.name())
                .field(UiField.select("builtinName", "Tool",
                        isNew ? null : tool.name(), builtinOptions)
                        .asEditable()
                        .hint("Select a registered tool, or choose '(custom name)' and fill Name below"))
                .field(UiField.text("name", "Name (custom)",
                        isNew ? null : tool.name())
                        .asEditable()
                        .hint("Leave blank to use the selected name — or type a different one to "
                                + "expose the selected tool under YOUR name (e.g. search_project_docs "
                                + "for a pinned vector_search); the LLM sees only your name"))
                .field(UiField.text("description", "Description",
                        isNew ? null : tool.description())
                        .asEditable())
                .field(UiField.textarea("overrides", "Overrides (JSON)",
                        overridesJson)
                        .asEditable()
                        .hint("JSON object. Tool config, e.g. {\"baseDir\":\"/tmp\"} (file tools) or "
                                + "{\"network\":\"bridge\"} (code_execute). "
                                + "{\"params\":{...}} pins tool parameters: pinned values are hidden "
                                + "from the LLM and always enforced, e.g. {\"params\":{\"language\":\"python\"}}"))
                .field(UiField.bool("enabled", "Enabled",
                        isNew || tool.enabled()).asEditable())
                .field(UiField.bool("deferred", "Deferred (via tool search only)",
                        !isNew && tool.deferred())
                        .asEditable()
                        .hint("Deferred tools are not offered to the LLM up front — they are found "
                                + "and activated through tool_search (enable Tool Search on the agent). "
                                + "Keeps large tool sets out of the context until needed"))
                .field(UiField.bool("needsApproval", "Needs approval",
                        !isNew && tool.needsApproval())
                        .asEditable()
                        .hint("A human must approve every call of this tool before it runs — the "
                                + "chat shows an approval card with the concrete arguments. "
                                + "'Allow for this session' silences further asking per session"))
                .field(UiField.number("maxResultChars", "Max result chars",
                        isNew ? null : tool.maxResultChars())
                        .asEditable()
                        .hint("Cut this tool's result after N characters at persist time (with a "
                                + "visible truncation note) — the cut part is really gone. Leave "
                                + "blank for no per-tool cap; a runtime safety cap of 100k always "
                                + "applies. For lossless shrinking use the memory strategy's "
                                + "tool-result compression instead"));

        String backHref = "/admin/agents/" + agent.id() + "?section=tools"
                + (isNew ? "" : "&row=" + tool.id());

        form.action(UiAction.primary("save", "Save").icon("save")
                        .onClick(isNew
                                ? trigger(on(AgentUiController.class).addTool(agent.id(), null, null), id())
                                : trigger(on(AgentUiController.class)
                                          .updateTool(agent.id(), tool.id(), null, null), id())))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .dispatch("GET", backHref))
                .link(UiLink.of("back", backHref, "← Back to Agent"));

        // The form keeps the component id — the Save trigger collects its
        // payload from that node; the parameters and config-overrides tables
        // sit beside it so the Overrides JSON field is self-documenting.
        UiNode schemaTable = ToolSchemaTable.render(id() + "-schema", schema);
        UiNode overridesTable = isNew ? null : ToolSchemaTable.render(id() + "-ov-schema",
                toolRegistry.overridesSchema(tool.name()), "Config Overrides");
        if (schemaTable == null && overridesTable == null) {
            return form;
        }
        UiStack stack = UiStack.of(id() + "-wrap").gap(16).child(form);
        if (schemaTable != null) stack.child(schemaTable);
        if (overridesTable != null) stack.child(overridesTable);
        return stack;
    }
}
