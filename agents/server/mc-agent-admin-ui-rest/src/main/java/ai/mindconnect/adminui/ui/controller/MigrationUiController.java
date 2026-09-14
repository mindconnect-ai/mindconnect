package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.MigrationService;
import ai.mindconnect.adminui.ui.page.MigrationListPage;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-UI endpoints for reviewing and applying migrations — pending imports of
 * bundled initial data ({@code classpath:initial-data/**}) that is new or
 * differs from what is stored. Backs the "Migrations" tab.
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
    public UiPage apply(@RequestParam("id") String id) {
        migrationService.apply(id);
        return list();
    }

    /** Applies one field of a pending migration; the rest of the stored record is kept. */
    @PostMapping("/apply-field")
    public UiPage applyField(@RequestParam("id") String id, @RequestParam("field") String field) {
        migrationService.applyField(id, field);
        return list();
    }

    @PostMapping("/apply-all")
    public UiPage applyAll() {
        migrationService.applyAll();
        return list();
    }
}
