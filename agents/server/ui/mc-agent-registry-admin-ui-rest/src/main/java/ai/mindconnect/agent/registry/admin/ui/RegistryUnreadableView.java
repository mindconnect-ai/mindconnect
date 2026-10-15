package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;

/**
 * A registry that could not be read — the wrong ref, a moved index, a private
 * repository whose token is not set, no outbound access at all.
 *
 * <p>Its own screen rather than a toast: the reason is the whole message, and
 * the two useful next steps (edit the registry, try again) belong next to it.
 */
final class RegistryUnreadableView {

    private final RegistrySource source;
    private final String reason;

    RegistryUnreadableView(RegistrySource source, String reason) {
        this.source = source;
        this.reason = reason;
    }

    UiNode render() {
        String base = RegistryListView.BASE + "/api/" + source.id().value();
        return UiList.of("registry-unreadable", source.name())
                .icon("box")
                .action(UiAction.primary("retry", "Try again").icon("refresh")
                        .dispatch("POST", base + "/refresh"))
                .action(UiAction.secondary("edit", "Edit registry").icon("edit")
                        .dispatch("GET", base + "/edit"))
                .action(UiAction.secondary("back", "All registries").icon("back")
                        .dispatch("GET", RegistryListView.BASE + "/api"))
                .item(UiList.Item.of("reason", "Cannot read " + source.coordinates())
                        .description(reason == null ? "No reason given." : reason));
    }
}
