package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;

import java.util.List;

/**
 * The registries this installation knows, one row each: what it is called,
 * which repository and ref it reads, and whether it is switched on.
 *
 * <p>An installation starts with none. That is the honest default — a registry
 * is somebody's repository of agents and prompts, and which ones to trust is
 * not a decision this software can make on an operator's behalf.
 */
final class RegistryListView {

    static final String BASE = "/registry";

    private final List<RegistrySource> sources;

    RegistryListView(List<RegistrySource> sources) {
        this.sources = sources;
    }

    UiList render() {
        UiList list = UiList.of("registry-list", "Registries")
                .icon("box")
                .action(UiAction.primary("add", "Add registry").icon("add")
                        .dispatch("GET", BASE + "/api/new"));

        if (sources.isEmpty()) {
            list.item(UiList.Item.of("none", "No registry configured")
                    .description("A registry is a GitHub project holding an index of LLM configs, "
                            + "agents, workflows and packages. Add one by its owner/repo — "
                            + "pin a tag for a registry you do not control."));
            return list;
        }

        for (RegistrySource source : sources) {
            list.item(UiList.Item.of(source.id().value(), source.name())
                    .icon("box")
                    .description(describe(source))
                    .href(BASE + "/" + source.id().value())
                    .action(UiAction.secondary("edit-" + source.id().value(), "Edit").icon("edit")
                            .dispatch("GET", BASE + "/api/" + source.id().value() + "/edit"))
                    .action(UiAction.danger("delete-" + source.id().value(), "Remove").icon("delete")
                            .confirm("Remove the registry '" + source.name() + "'? "
                                    + "What was imported from it stays.")
                            .dispatch("DELETE", BASE + "/api/" + source.id().value())));
        }
        return list;
    }

    /** "owner/repo@main · registry.json · private (GH_TOKEN)" — or why it is not read. */
    private static String describe(RegistrySource source) {
        StringBuilder text = new StringBuilder(source.coordinates());
        if (!RegistrySource.DEFAULT_INDEX_PATH.equals(source.indexPath())) {
            text.append(" · ").append(source.indexPath());
        }
        if (source.tokenEnvVar() != null) {
            text.append(" · private (").append(source.tokenEnvVar()).append(')');
        }
        if (!source.enabled()) {
            text.append(" · disabled");
        }
        return text.toString();
    }
}
