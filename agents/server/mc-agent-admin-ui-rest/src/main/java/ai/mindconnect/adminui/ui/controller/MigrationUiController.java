package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.MigrationService;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.ui.component.MigrationListComponent;
import ai.mindconnect.adminui.ui.page.MigrationListPage;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-UI endpoints for reviewing and applying migrations — pending imports of
 * bundled initial data ({@code classpath:initial-data/**}) that is new or
 * differs from what is stored. Backs the "Migrations" tab.
 *
 * <p>The applies answer with a {@link UiPatch}, not the page: the list stays
 * where it is and only the applied item (or field) leaves it.
 */
@RestController
@RequestMapping("/admin/api/migrations")
public class MigrationUiController {

    private final MigrationService migrationService;

    public MigrationUiController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping
    public UiPage list() {
        return new MigrationListPage(migrationService.pending()).render();
    }

    @PostMapping("/apply")
    public UiPatch apply(@RequestParam("id") String id) {
        PendingMigration applied = find(id);
        if (applied == null) return refreshed();
        migrationService.apply(id);
        return new MigrationListComponent(migrationService.pending())
                .afterApply(applied, "Applied '" + applied.name() + "'");
    }

    /** Applies one field of a pending migration; the rest of the stored record is kept. */
    @PostMapping("/apply-field")
    public UiPatch applyField(@RequestParam("id") String id, @RequestParam("field") String field) {
        PendingMigration applied = find(id);
        if (applied == null) return refreshed();
        migrationService.applyField(id, field);
        return new MigrationListComponent(migrationService.pending())
                .afterApply(applied, "Applied '" + field + "' of '" + applied.name() + "'");
    }

    @PostMapping("/apply-all")
    public UiPatch applyAll() {
        migrationService.applyAll();
        return refreshed();
    }

    /** The pending migration with this id, or null when it is no longer pending (a stale screen). */
    private PendingMigration find(String id) {
        return migrationService.pending().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** The whole list re-rendered in place — still a patch, not a page load. */
    private UiPatch refreshed() {
        var list = new MigrationListComponent(migrationService.pending());
        return UiPatch.of().patch(UiPatch.Operation.replace(list.id(), list.render()));
    }
}
