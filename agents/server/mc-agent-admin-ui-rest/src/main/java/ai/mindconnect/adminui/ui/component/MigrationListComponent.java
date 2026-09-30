package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.MigrationService.EntityType;
import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.service.MigrationService.Status;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiToast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists pending migrations (bundled initial-data that is new or differs from
 * what is stored), grouped into one collapsible section per entity type — the
 * same layout the tool catalog uses. Each changed record shows its field-level
 * differences as a Before/After table; each item has an Apply action, each
 * diff row an Apply of its own (that one field only, the rest of the stored
 * record — an API key, say — is kept), and a header "Apply all" applies every
 * pending change at once.
 *
 * <p>An apply answers with a patch rather than the whole page (see
 * {@link #afterApply}): the applied item leaves the list, the counts follow,
 * and the rest of the screen — which sections are open, the scroll position —
 * stays as it was.
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

        list.action(applyAll(pending.size()));

        // One collapsible section per entity type (open by default), in the
        // order the service emits them.
        Map<EntityType, List<PendingMigration>> byType = new LinkedHashMap<>();
        for (PendingMigration p : pending) {
            byType.computeIfAbsent(p.entityType(), t -> new ArrayList<>()).add(p);
        }
        byType.forEach((type, typePending) -> {
            String gid = groupId(type);
            var groupList = UiList.of(gid + "-list", "");
            for (PendingMigration p : typePending) {
                groupList.item(migrationItem(p));
            }
            // Empty label: the collapse summary is the heading; a label would
            // render the type name a second time inside the open section.
            list.item(UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(groupSummary(type, typePending.size()), false, gid + "-sum"));
        });
        return list;
    }

    /**
     * What the screen needs after {@code applied} (one whole migration, or one
     * field of it) was applied, given what is still pending now. The item is
     * swapped for its remaining diff, or leaves the list when nothing of it is
     * pending any more — taking its section with it when that was the last of
     * its type — and the section count and "Apply all" follow. Once nothing at
     * all is pending the list is rendered afresh, which is the "up to date"
     * message.
     */
    public UiPatch afterApply(PendingMigration applied, String message) {
        var patch = UiPatch.of().toast(UiToast.success(message));
        if (pending.isEmpty()) {
            return patch.patch(UiPatch.Operation.replace(id(), render()));
        }
        var stillPending = pending.stream().filter(p -> p.id().equals(applied.id())).findFirst();
        if (stillPending.isPresent() && !stillPending.get().diffs().isEmpty()) {
            // Only part of it was applied: the diff table loses those rows.
            PendingMigration p = stillPending.get();
            patch.patch(UiPatch.Operation.replace(diffTableId(p), diffTable(p)));
            patch.patch(UiPatch.Operation.replace(itemSummaryId(p), UiText.of(itemSummaryId(p), itemSummary(p))));
        } else if (stillPending.isEmpty()) {
            long left = pending.stream().filter(p -> p.entityType() == applied.entityType()).count();
            String gid = groupId(applied.entityType());
            if (left == 0) {
                patch.patch(UiPatch.Operation.remove(gid));
            } else {
                patch.patch(UiPatch.Operation.remove(applied.id()));
                patch.patch(UiPatch.Operation.replace(gid + "-sum",
                        UiText.of(gid + "-sum", groupSummary(applied.entityType(), (int) left))));
            }
        }
        // Replaced whole: header actions are not in the renderer's model
        // registry, so a MERGE of just the label would not find its target.
        var applyAll = applyAll(pending.size());
        return patch.patch(UiPatch.Operation.replace(applyAll.getId(), applyAll));
    }

    private static UiAction applyAll(int count) {
        return UiAction.primary("apply-all", "Apply all (" + count + ")")
                .confirm("Apply all " + count + " pending migration(s)?")
                .dispatch("POST", "/admin/api/migrations/apply-all");
    }

    private static String groupId(EntityType type) {
        return "migration-group-" + type.slug();
    }

    private static String groupSummary(EntityType type, int count) {
        return type.label() + "  (" + count + ")";
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
            item.collapsible(itemSummary(p), false, itemSummaryId(p));
        } else {
            item.description(p.status() == Status.NEW
                    ? "New record — will be imported."
                    : "Differs from stored version.");
        }
        return item;
    }

    private static String itemSummary(PendingMigration p) {
        String badge = p.status() == Status.NEW ? "[NEW]" : "[CHANGED]";
        return badge + " " + p.name() + " — " + p.diffs().size() + " field(s)";
    }

    private static String itemSummaryId(PendingMigration p) {
        return p.id() + "-sum";
    }

    private static String diffTableId(PendingMigration p) {
        return "migration-diff-" + p.id();
    }

    /**
     * Field-level differences as a Before (stored) / After (bundled) table.
     * Every row carries its own Apply: the client fills {@code {id}} with the
     * row id, which is the (URL-encoded) field name.
     */
    private static UiTable diffTable(PendingMigration p) {
        var table = UiTable.of(diffTableId(p), null).stackOnMobile(true)
                .column(UiColumn.text("field", "Field"))
                .column(UiColumn.text("before", "Before (stored)"))
                .column(UiColumn.text("after", "After (bundled)"))
                .rowAction(UiAction.secondary("apply-field", "Apply").icon("check")
                        .confirm("Apply only this field for '" + p.name() + "'? Everything else stays as stored.")
                        .dispatch("POST", "/admin/api/migrations/apply-field?id=" + encode(p.id()) + "&field={id}"));
        table.withCssClass("migration-diff");
        for (FieldDiff d : p.diffs()) {
            table.row(Map.of(
                    "id",     encode(d.field()),
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
