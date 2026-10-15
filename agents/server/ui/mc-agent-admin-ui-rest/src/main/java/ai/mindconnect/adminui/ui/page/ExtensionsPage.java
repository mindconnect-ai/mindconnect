package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ExtensionListComponent;
import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;
import java.util.Map;

/**
 * The Extensions screen at {@code /admin/extensions}: what the classpath
 * brought along, and whether each of it is on in the namespace at hand.
 */
public final class ExtensionsPage extends AdminPage {

    /** The page URL; the controller answers JSON on the same URL. */
    public static final String NAVIGATE = "/admin/extensions";

    private final List<ExtensionService.Status> extensions;
    private final List<ExtensionRegistry.Problem> problems;
    private final List<ClasspathAudit.UnmanagedJar> unmanaged;
    private final Map<String, ContributionChecker.Report> reports;
    private final boolean inBrandNamespace;

    public ExtensionsPage(List<ExtensionService.Status> extensions,
                          List<ExtensionRegistry.Problem> problems,
                          List<ClasspathAudit.UnmanagedJar> unmanaged,
                          Map<String, ContributionChecker.Report> reports,
                          boolean inBrandNamespace) {
        this.extensions = extensions;
        this.problems = problems;
        this.unmanaged = unmanaged;
        this.reports = reports;
        this.inBrandNamespace = inBrandNamespace;
    }

    @Override
    public UiPage render() {
        return UiPage.of(NAVIGATE,
                new ExtensionListComponent(extensions, problems, unmanaged, reports, inBrandNamespace).render());
    }
}
