package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.common.Page;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Top-level list of agents: a search field, per-row Copy / Delete actions,
 * header "New Agent" action, and pagination that carries the search filter.
 */
public final class AgentListComponent implements UiComponent {

    private final Page<AgentDefinition> page;
    private final String query;

    public AgentListComponent(Page<AgentDefinition> page, String query) {
        this.page = page;
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
                .onChange(UiTrigger.api("POST", "/admin/api/agents/search", formId)));
        return form;
    }

    private UiList list() {
        var list = UiList.of(id(), "Agents")
                .icon("bot")
                .headerExtra(searchForm())
                .action(UiAction.primary("create", "New Agent").icon("add")
                        .dispatch("GET", "/admin/api/agents/new"));

        for (AgentDefinition a : page.items()) {
            list.item(
                UiList.Item.of(a.id().toString(), a.name())
                    .labelNode(headerNode(a))
                    .description(a.description())
                    .href("/admin/agents/" + a.id())
                    // Straight into a conversation: starts a fresh session
                    // with this agent and lands on its chat page.
                    .action(UiAction.primary("chat", "Chat").icon("chat")
                            .dispatch("POST", "/admin/api/agents/" + a.id() + "/sessions"))
                    .action(UiAction.secondary("copy", "Copy").icon("copy")
                            .dispatch("POST", "/admin/api/agents/" + a.id() + "/copy"))
                    .action(UiAction.danger("delete", "Delete").icon("delete")
                            .confirm("Delete agent '" + a.name() + "'?")
                            .dispatch("DELETE", "/admin/api/agents/" + a.id()))
            );
        }
        // Pagination buttons dispatch through the standard APPLY_RESPONSE
        // path with {page} substituted by the renderer. The search filter
        // rides along so paging stays within the filtered result.
        String pageUrl = "/admin/agents?page={page}";
        if (query != null && !query.isBlank()) {
            pageUrl += "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        list.paginate(page.page(), page.size(), page.total(), UiTrigger.go(pageUrl));
        return list;
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
                .child(name);
        String llm = a.llmConfigName();
        if (llm != null && !llm.isBlank()) {
            stack.child(UiText.of("agent-llm-" + a.id(), llm)
                    .<UiText>withCssClass("agent-llm-badge"));
        }
        return stack;
    }
}
