package ai.mindconnect.adminui.ui;

import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.service.ExtensionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
 * the namespace only when its {@code roles} name {@code USER}.
 *
 * <p>Ordered last: a bean the jar registers under the same id comes first,
 * and the layout keeps the first of two entries with one id.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ExtensionMenuContribution implements AdminMenuContribution {

    private static final Logger log = LoggerFactory.getLogger(ExtensionMenuContribution.class);

    private final ObjectProvider<ExtensionService> extensions;

    public ExtensionMenuContribution(ObjectProvider<ExtensionService> extensions) {
        this.extensions = extensions;
    }

    @Override
    public List<Entry> entries(boolean admin) {
        ExtensionService service = extensions.getIfAvailable();
        if (service == null) return List.of();
        List<ExtensionService.Status> statuses;
        try {
            statuses = service.list();
        } catch (IllegalStateException noScope) {
            // Off a bound scope — a scheduled render — there is no namespace to have decided.
            log.debug("No scope while building the menu — no extension entries");
            return List.of();
        }
        return entries(statuses, admin);
    }

    /** The entries for these extensions as they stand; package-private for tests. */
    static List<Entry> entries(List<ExtensionService.Status> statuses, boolean admin) {
        List<Entry> links = new ArrayList<>();
        Map<String, List<Entry>> groupMembers = new LinkedHashMap<>();
        Map<String, ExtensionManifest.Ui.MenuEntry> groupHeads = new LinkedHashMap<>();
        // The first entry that names an icon for its group gives it one, whichever extension that is.
        Map<String, String> groupIcons = new LinkedHashMap<>();
        for (ExtensionService.Status status : statuses) {
            if (!status.enabled()) continue;
            for (ExtensionManifest.Ui.MenuEntry entry : status.manifest().contributes().ui().menu()) {
                if (!entry.isRenderable()) continue;
                if (!admin && !entry.forUsers()) continue;
                Entry link = Entry.of(entry.id(), entry.label(), entry.href(), entry.icon());
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
        List<Entry> all = new ArrayList<>(links);
        groupMembers.forEach((group, members) -> {
            ExtensionManifest.Ui.MenuEntry head = groupHeads.get(group);
            String label = head.groupLabel() == null || head.groupLabel().isBlank() ? group : head.groupLabel();
            all.add(Entry.group(group, label, groupIcons.get(group), members));
        });
        return all;
    }
}
