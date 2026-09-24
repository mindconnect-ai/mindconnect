package ai.mindconnect.adminui.ui;

import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.service.ExtensionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sidebar entries the manifests declare with a label and an href — the
 * host renders those itself, so an extension needs no
 * {@link AdminMenuContribution} bean of its own for a plain link. Entries
 * with a {@code group} join that group; a group the menu does not have yet
 * is created under {@code groupLabel}. Only extensions that are on in the
 * namespace at hand contribute, and an entry is offered to a plain user of
 * the namespace only when its {@code roles} name {@code USER} — the route
 * then being open to users as well.
 *
 * <p>Not a contribution bean: {@link AdminLayoutFactory} asks for these
 * after the beans' entries, explicitly, so a bean's entry always comes
 * first and wins over a manifest's with the same id.
 */
public final class ExtensionMenuContribution {

    private ExtensionMenuContribution() {
    }

    /** The entries for these extensions as they stand. */
    public static List<AdminMenuContribution.Entry> entries(List<ExtensionService.Status> statuses, boolean admin) {
        List<AdminMenuContribution.Entry> links = new ArrayList<>();
        Map<String, List<AdminMenuContribution.Entry>> groupMembers = new LinkedHashMap<>();
        Map<String, ExtensionManifest.Ui.MenuEntry> groupHeads = new LinkedHashMap<>();
        // The first entry that names an icon for its group gives it one, whichever extension that is.
        Map<String, String> groupIcons = new LinkedHashMap<>();
        for (ExtensionService.Status status : statuses) {
            if (!status.enabled()) continue;
            for (ExtensionManifest.Ui.MenuEntry entry : status.manifest().contributes().ui().menu()) {
                if (!entry.isRenderable()) continue;
                if (!admin && !entry.forUsers()) continue;
                AdminMenuContribution.Entry link = AdminMenuContribution.Entry.of(entry.id(), entry.label(), entry.href(), entry.icon());
                if (entry.group() == null || entry.group().isBlank()) {
                    links.add(link);
                } else {
                    groupHeads.putIfAbsent(entry.group(), entry);
                    if (entry.groupIcon() != null && !entry.groupIcon().isBlank()) {
                        groupIcons.putIfAbsent(entry.group(), entry.groupIcon());
                    }
                    groupMembers.computeIfAbsent(entry.group(), g -> new ArrayList<>()).add(link);
                }
            }
        }
        List<AdminMenuContribution.Entry> all = new ArrayList<>(links);
        groupMembers.forEach((group, members) -> {
            ExtensionManifest.Ui.MenuEntry head = groupHeads.get(group);
            String label = head.groupLabel() == null || head.groupLabel().isBlank() ? group : head.groupLabel();
            all.add(AdminMenuContribution.Entry.group(group, label, groupIcons.get(group), members));
        });
        return all;
    }
}
