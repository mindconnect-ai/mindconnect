package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.adminui.ui.controller.AgentUiController;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.chatui.ui.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiIcon;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level list of agents: a search field, per-row Chat / Copy / Delete
 * actions and a header "New Agent" action, with the rows filed under their
 * {@link AgentDefinition#group()} the same way the tool catalog files tools
 * under theirs.
 *
 * <p>No pagination. The list is short by design — a handful of assistants, the
 * specialists they delegate to, and the few agents the runtime calls on its
 * own — and paging cuts across the grouping: a rubric would appear on one page
 * and continue on the next, which reads as two different rubrics.
 */
public final class AgentListComponent implements UiComponent {

    /**
     * The seeded rubrics, in the order they earn attention: the agents a
     * person chats with, then the specialists those delegate to, then the ones
     * the runtime calls on its own. Anything else follows, alphabetically.
     */
    private static final List<String> GROUP_ORDER = List.of("assistants", "sub-agents", "utilities");

    private final List<AgentDefinition> agents;
    private final String query;

    public AgentListComponent(List<AgentDefinition> agents) {
        this(agents, null);
    }

    public AgentListComponent(List<AgentDefinition> agents, String query) {
        this.agents = agents;
        this.query = query;
    }

    @Override
    public String id() {
        return "agent-list";
    }

    @Override
    public UiNode render() {
        return list();
    }

    /** Compact search in the list header: typing + Enter (the field's change event) re-renders filtered. */
    private UiForm searchForm() {
        String formId = "agent-search";
        UiForm form = UiForm.of(formId, null);
        form.field(UiField.text("q", "", query)
                .asEditable()
                .icon("search")
                .placeholder("Search name or description…")
                .onChange(trigger(on(AgentUiController.class).search(null), formId)));
        return form;
    }

    private UiList list() {
        var list = UiList.of(id(), "Agents  (" + agents.size() + ")")
                .icon("bot")
                .headerExtra(searchForm())
                .action(UiAction.primary("create", "New Agent").icon("add")
                        .onClick(trigger(on(AgentUiController.class).newForm())));

        if (agents.isEmpty()) {
            list.item(UiList.Item.of("empty", "No agents yet")
                    .description(query == null || query.isBlank()
                            ? "Create one, or install the seeded agents from the Migrations page."
                            : "Nothing matches “" + query + "”."));
            return list;
        }

        for (Map.Entry<String, List<AgentDefinition>> e : byGroup().entrySet()) {
            String gid = "agent-group-" + e.getKey().toLowerCase().replaceAll("\\W+", "-");
            var groupList = UiList.of(gid + "-list", "");
            for (AgentDefinition a : e.getValue()) {
                groupList.item(row(a));
            }
            // Closed by default, like the tool catalog: the rubrics are the
            // map, and a page that opens as three headings tells you what is
            // here in one glance instead of a wall of descriptions. The empty
            // label keeps the group name from being repeated inside the
            // section it already titles.
            list.item(UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(ToolCatalogComponent.displayGroup(e.getKey())
                            + "  (" + e.getValue().size() + ")", false, gid + "-sum"));
        }
        return list;
    }

    /** Agents by rubric: the seeded order first, then any other rubric alphabetically. */
    private Map<String, List<AgentDefinition>> byGroup() {
        Map<String, List<AgentDefinition>> byGroup = new LinkedHashMap<>();
        agents.stream()
                .sorted(Comparator
                        .comparingInt((AgentDefinition a) -> {
                            int i = GROUP_ORDER.indexOf(a.groupOrDefault());
                            return i < 0 ? GROUP_ORDER.size() : i;
                        })
                        .thenComparing(AgentDefinition::groupOrDefault)
                        .thenComparing(a -> a.name() == null ? "" : a.name(),
                                String.CASE_INSENSITIVE_ORDER))
                .forEach(a -> byGroup.computeIfAbsent(a.groupOrDefault(), g -> new ArrayList<>()).add(a));
        return byGroup;
    }

    private static UiList.Item row(AgentDefinition a) {
        return UiList.Item.of(a.id().toString(), a.name())
                .labelNode(headerNode(a))
                .description(a.description())
                .href("/admin/agents/" + a.id())
                // Straight into a conversation: starts a fresh session
                // with this agent and lands on its chat page.
                .action(UiAction.primary("chat", "Chat").icon("chat")
                        .onClick(trigger(on(ChatUiController.class).startSession(a.id(), null))))
                .action(UiAction.secondary("copy", "Copy").icon("copy")
                        .onClick(trigger(on(AgentUiController.class).copy(a.id()))))
                .action(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete agent '" + a.name() + "'?")
                        .onClick(trigger(on(AgentUiController.class).delete(a.id()))));
    }

    /**
     * Row header: the agent name plus a small LLM-config badge to its right.
     * The badge is a {@link UiText} styled via the {@code agent-llm-badge} CSS
     * class; the whole header is a horizontal {@link UiStack} so name and badge
     * sit on one line. When no config is set, just the plain name node.
     */
    private static UiStack headerNode(AgentDefinition a) {
        var name = UiText.of("agent-name-" + a.id(), a.name())
                .<UiText>withCssClass("agent-name");
        var stack = UiStack.of("agent-head-" + a.id())
                .direction(UiStack.Direction.HORIZONTAL)
                .gap(8)
                .<UiStack>withCssClass("agent-head")
                // The item's own icon slot goes unrendered once a labelNode
                // takes over the label, so the icon rides in the node itself.
                .child(UiIcon.of("agent-icon-" + a.id(), a.iconOrDefault()))
                .child(name);
        String llm = a.llmConfigName();
        if (llm != null && !llm.isBlank()) {
            stack.child(UiText.of("agent-llm-" + a.id(), llm)
                    .<UiText>withCssClass("agent-llm-badge"));
        }
        return stack;
    }
}
