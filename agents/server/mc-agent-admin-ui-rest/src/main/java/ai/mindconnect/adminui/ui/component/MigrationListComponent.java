package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.MigrationService.EntityType;
import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.service.MigrationService.Status;
import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists pending migrations (bundled initial-data that is new or differs from
 * what is stored), grouped into one collapsible section per entity type — the
 * same layout the tool catalog uses. Each changed record shows its field-level
 * differences as a Before/After table; each item has an Apply action and a
 * header "Apply all" applies every pending change at once.
 */
public final class MigrationListComponent implements UiComponent {

    private final List<PendingMigration> pending;

    public MigrationListComponent(List<PendingMigration> pending) {
        this.pending = pending;
    }

    @Override
    public String id() {
        return "migration-list";
    }

    @Override
    public UiList render() {
        var list = UiList.of(id(), "Migrations").icon("refresh");

        if (pending.isEmpty()) {
            list.item(UiList.Item.of("none", "Everything is up to date")
                    .description("No bundled initial data differs from what is stored."));
            return list;
        }

        list.action(UiAction.primary("apply-all", "Apply all (" + pending.size() + ")")
                .confirm("Apply all " + pending.size() + " pending migration(s)?")
                .dispatch("POST", "/admin/api/migrations/apply-all"));

        // One collapsible section per entity type (open by default), in the
        // order the service emits them.
        Map<EntityType, List<PendingMigration>> byType = new LinkedHashMap<>();
        for (PendingMigration p : pending) {
            byType.computeIfAbsent(p.entityType(), t -> new ArrayList<>()).add(p);
        }
        byType.forEach((type, typePending) -> {
            String gid = "migration-group-" + type.slug();
            var groupList = UiList.of(gid + "-list", "");
            for (PendingMigration p : typePending) {
                groupList.item(migrationItem(p));
            }
            // Empty label: the collapse summary is the heading; a label would
            // render the type name a second time inside the open section.
            list.item(UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(type.label() + "  (" + typePending.size() + ")", false, gid + "-sum"));
        });
        return list;
    }

    private static UiList.Item migrationItem(PendingMigration p) {
        String badge = p.status() == Status.NEW ? "[NEW]" : "[CHANGED]";
        boolean hasDiff = !p.diffs().isEmpty();
        // Collapsible items are represented by their summary line alone (badge,
        // name, field count) — a label would repeat it as a second heading.
        var item = UiList.Item.of(p.id(), hasDiff ? "" : badge + " " + p.name())
                .action(UiAction.primary("apply", "Apply").icon("check")
                        .confirm("Apply migration for '" + p.name() + "'?")
                        .dispatch("POST", "/admin/api/migrations/apply?id=" + encode(p.id())));

        if (hasDiff) {
            item.content(diffTable(p));
            item.collapsible(badge + " " + p.name() + " — " + p.diffs().size() + " field(s)", false);
        } else {
            item.description(p.status() == Status.NEW
                    ? "New record — will be imported."
                    : "Differs from stored version.");
        }
        return item;
    }

    /** Field-level differences as a Before (stored) / After (bundled) table. */
    private static UiTable diffTable(PendingMigration p) {
        var table = UiTable.of("migration-diff-" + p.id(), null)
                .column(UiColumn.text("field", "Field"))
                .column(UiColumn.text("before", "Before (stored)"))
                .column(UiColumn.text("after", "After (bundled)"));
        table.withCssClass("migration-diff");
        for (FieldDiff d : p.diffs()) {
            table.row(Map.of(
                    "field",  d.field(),
                    "before", d.before() == null ? "—" : d.before(),
                    "after",  d.after()  == null ? "—" : d.after()));
        }
        return table;
    }

    /** Path-segment encode the migration id (name may contain spaces or ':'). */
    private static String encode(String id) {
        return java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
    }
}
