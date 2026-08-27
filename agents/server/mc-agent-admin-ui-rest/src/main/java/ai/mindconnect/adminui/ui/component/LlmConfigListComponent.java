package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;

import java.util.List;

/**
 * Top-level list of all stored LLM configurations. Each item links to
 * its detail view; a header action opens the "new config" form, each
 * row has a Delete action.
 */
public final class LlmConfigListComponent implements UiComponent {

    private final List<LlmConfig> configs;

    public LlmConfigListComponent(List<LlmConfig> configs) {
        this.configs = configs;
    }

    @Override
    public String id() {
        return "llm-config-list";
    }

    @Override
    public UiList render() {
        var list = UiList.of(id(), "LLM Configurations")
                .icon("ai")
                .action(UiAction.primary("create", "New LLM Config").icon("add")
                        .dispatch("GET", "/admin/api/llm-configs/new"));

        for (LlmConfig c : configs) {
            String description = c.isAlias()
                    ? "alias → " + c.delegatesTo()
                    : (c.provider() != null ? c.provider().name() : "?") + " · " + c.model();
            list.item(
                UiList.Item.of(c.id().toString(), c.name())
                    .description(description)
                    .href("/admin/llm-configs/" + c.id())
                    .action(UiAction.danger("delete", "Delete").icon("delete")
                            .confirm("Delete config '" + c.name() + "'?")
                            .dispatch("DELETE", "/admin/api/llm-configs/" + c.id()))
            );
        }
        return list;
    }
}
