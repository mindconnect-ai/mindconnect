package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ToolCatalogComponent;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;

/** Read-only catalog of all available runtime tools at {@code /admin/tools}. */
public final class ToolListPage extends AdminPage {

    private final List<ToolCatalogComponent.Entry> entries;
    private final String query;

    public ToolListPage(List<ToolCatalogComponent.Entry> entries, String query) {
        this.entries = entries;
        this.query = query;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/tools",
                new ToolCatalogComponent(entries, query).render());
    }
}
