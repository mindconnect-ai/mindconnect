package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.MigrationListComponent;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;

/** Pending-migrations review page at {@code /admin/migrations}. */
public final class MigrationListPage extends AdminPage {

    private final List<PendingMigration> pending;

    public MigrationListPage(List<PendingMigration> pending) {
        this.pending = pending;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/migrations",
                new MigrationListComponent(pending).render());
    }
}
