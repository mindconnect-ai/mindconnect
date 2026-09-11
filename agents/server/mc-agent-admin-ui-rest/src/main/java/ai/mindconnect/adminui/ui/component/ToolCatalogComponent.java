package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only catalog of every tool the runtime's {@link ai.mindconnect.agent.tool.ToolRegistry}
 * knows about — the union of built-in {@code ToolFactory} tools and
 * {@code MultiToolProvider}-contributed tools. Unlike the per-agent tool list
 * (which is editable), this is purely informational: it shows what's available
 * to wire onto an agent, with each tool's description, its parameters and
 * its declared config overrides as readable tables in a collapsible body.
 */
public final class ToolCatalogComponent implements UiComponent {

    /**
     * One catalog row: group (rubric), optional subgroup (the source within
     * that rubric — an MCP server, say), name, description, parameters
     * schema and declared overrides schema.
     */
    public record Entry(String group, String subgroup, String name, String description,
                        Object schema, Object overridesSchema, boolean enabled) {

        public Entry(String group, String subgroup, String name, String description,
                     Object schema, Object overridesSchema) {
            this(group, subgroup, name, description, schema, overridesSchema, true);
        }

        public Entry(String group, String name, String description, Object schema, Object overridesSchema) {
            this(group, null, name, description, schema, overridesSchema, true);
        }
    }

    private final List<Entry> entries;
    private final String query;
    /** Extra header actions a section contributes, e.g. "register an MCP server". */
    private final List<UiAction> headerActions;

    public ToolCatalogComponent(List<Entry> entries, String query) {
        this(entries, query, List.of());
    }

    public ToolCatalogComponent(List<Entry> entries, String query, List<UiAction> headerActions) {
        this.entries = entries;
        this.query = query;
        this.headerActions = headerActions == null ? List.of() : headerActions;
    }

    @Override
    public String id() {
        return "tool-catalog";
    }

    @Override
    public UiNode render() {
        return catalogList();
    }

    /** Compact search in the list header: typing + Enter (the field's change event) re-renders filtered. */
    private UiForm searchForm() {
        String formId = "tool-search";
        UiForm form = UiForm.of(formId, null);
        form.field(UiField.text("q", "", query)
                .asEditable()
                .icon("search")
                .placeholder("Search name or description…")
                .onChange(UiTrigger.api("POST", "/admin/api/tools/search", formId)));
        return form;
    }

    private UiList catalogList() {
        var list = UiList.of(id(), "Tools  (" + entries.size() + ")").icon("tools");
        list.withCssClass("tool-catalog");
        list.headerExtra(searchForm());
        headerActions.forEach(list::action);

        if (entries.isEmpty()) {
            list.item(UiList.Item.of("empty", "No tools registered")
                    .description("The runtime reported no tools. Check the tool modules on the classpath."));
            return list;
        }

        // One collapsible section per rubric (open by default), in the
        // (already sorted) order the entries arrive.
        Map<String, List<Entry>> byGroup = new LinkedHashMap<>();
        for (Entry e : entries) {
            byGroup.computeIfAbsent(e.group() == null ? "general" : e.group(), g -> new ArrayList<>()).add(e);
        }
        byGroup.forEach((group, groupEntries) -> {
            String gid = "tool-group-" + group.toLowerCase().replaceAll("\\W+", "-");
            var groupList = UiList.of(gid + "-list", "");
            // Within a group, tools that name a source are gathered under it —
            // one MCP server's forty tools are a section, not a wall. Tools
            // without a source keep their place directly in the group.
            Map<String, List<Entry>> bySubgroup = new LinkedHashMap<>();
            for (Entry e : groupEntries) {
                bySubgroup.computeIfAbsent(e.subgroup(), sg -> new ArrayList<>()).add(e);
            }
            bySubgroup.forEach((subgroup, subEntries) -> {
                if (subgroup == null) {
                    subEntries.forEach(e -> groupList.item(catalogItem(e)));
                    return;
                }
                String sid = gid + "-" + subgroup.toLowerCase().replaceAll("\\W+", "-");
                var subList = UiList.of(sid + "-list", "");
                subEntries.forEach(e -> subList.item(catalogItem(e)));
                groupList.item(UiList.Item.of(sid, "")
                        .content(subList)
                        .collapsible(subgroup + "  (" + subEntries.size() + ")", false, sid + "-sum"));
            });
            // Empty label: the collapse summary is the heading; a label would
            // repeat the group name inside the open section. Starts collapsed.
            list.item(UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(displayGroup(group) + "  (" + groupEntries.size() + ")", false, gid + "-sum"));
        });
        return list;
    }

    /** Groups are lowercase machine namespaces ({@code workflow}, {@code gmail}); capitalize for display. */
    public static String displayGroup(String group) {
        if (group == null || group.isBlank()) return "General";
        return Character.toUpperCase(group.charAt(0)) + group.substring(1);
    }

    private static UiList.Item catalogItem(Entry e) {
        var content = UiStack.of("tool-content-" + e.name()).gap(10);
        content.child(UiMarkdown.of("tool-md-" + e.name(), toolBody(e))
                .<UiMarkdown>withCssClass("task-card-body"));
        UiNode schemaTable = ToolSchemaTable.render("tool-schema-" + e.name(), e.schema());
        UiNode overridesTable = ToolSchemaTable.render("tool-ov-" + e.name(),
                e.overridesSchema(), "Config Overrides");
        if (schemaTable != null) content.child(schemaTable);
        if (overridesTable != null) content.child(overridesTable);
        content.child(UiAction.primary("test-" + e.name(), "Test")
                .dispatch("GET", "/admin/api/tools/" + e.name() + "/test"));
        content.child(UiAction.secondary("settings-" + e.name(), "Settings").icon("tools")
                .dispatch("GET", "/admin/api/tools/" + e.name() + "/settings"));
        return UiList.Item.of("tool-" + e.name(), e.name())
                .content(content)
                // Client-controlled collapse: starts closed, the user's
                // toggle survives any re-render (consistent with task cards).
                .collapsibleClient(summary(e), "tool-sum-" + e.name());
    }

    /**
     * Collapsed-state summary line: name + first line of the description.
     * A switched-off tool says so here, because this line is the whole row
     * an operator sees before opening it — and a row that looks like every
     * other one would make the catalog a list of lies.
     */
    private static String summary(Entry e) {
        String d = e.description() == null ? "" : e.description().strip();
        int nl = d.indexOf('\n');
        if (nl >= 0) d = d.substring(0, nl).strip();
        if (d.length() > 100) d = d.substring(0, 100) + "…";
        String head = e.enabled() ? e.name() : e.name() + "  (off)";
        return d.isBlank() ? head : head + " — " + d;
    }

    /** Description as markdown; parameters/overrides render as tables below. */
    private static String toolBody(Entry e) {
        if (e.description() != null && !e.description().isBlank()) {
            return e.description().strip();
        }
        return "_No description._";
    }
}
