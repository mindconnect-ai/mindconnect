package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The extensions the host found, one collapsible item each: name, version
 * and vendor in the heading; unfolded, two tables — <b>About</b> (what the
 * extension is and how it stands in this namespace) and <b>Brings</b> (what
 * its manifest contributes, each item with the classpath's verdict: found,
 * or not). Then the switch for the namespace at hand. Below the list, what
 * the start-up audit had to say — manifests that could not be read, jars
 * that bring providers without one — so an admin sees the classpath as the
 * host sees it.
 */
public final class ExtensionListComponent implements UiComponent {

    /** The page URL doubles as the API root: the same-URL section pattern. */
    public static final String API = "/admin/extensions";

    private final List<ExtensionService.Status> extensions;
    private final List<ExtensionRegistry.Problem> problems;
    private final List<ClasspathAudit.UnmanagedJar> unmanaged;
    /** The classpath's verdict per extension id, absent when nobody checked. */
    private final Map<String, ContributionChecker.Report> reports;
    /** Whether the viewer works in a brand's own namespace — then the brand's switches are offered. */
    private final boolean inBrandNamespace;

    public ExtensionListComponent(List<ExtensionService.Status> extensions,
                                  List<ExtensionRegistry.Problem> problems,
                                  List<ClasspathAudit.UnmanagedJar> unmanaged) {
        this(extensions, problems, unmanaged, Map.of());
    }

    public ExtensionListComponent(List<ExtensionService.Status> extensions,
                                  List<ExtensionRegistry.Problem> problems,
                                  List<ClasspathAudit.UnmanagedJar> unmanaged,
                                  Map<String, ContributionChecker.Report> reports) {
        this(extensions, problems, unmanaged, reports, false);
    }

    public ExtensionListComponent(List<ExtensionService.Status> extensions,
                                  List<ExtensionRegistry.Problem> problems,
                                  List<ClasspathAudit.UnmanagedJar> unmanaged,
                                  Map<String, ContributionChecker.Report> reports,
                                  boolean inBrandNamespace) {
        this.extensions = extensions == null ? List.of() : extensions;
        this.problems = problems == null ? List.of() : problems;
        this.unmanaged = unmanaged == null ? List.of() : unmanaged;
        this.reports = reports == null ? Map.of() : reports;
        this.inBrandNamespace = inBrandNamespace;
    }

    @Override
    public String id() {
        return "extensions";
    }

    @Override
    public UiStack render() {
        UiStack stack = UiStack.of(id());
        stack.child(extensionList());
        if (!problems.isEmpty()) stack.child(problemList());
        if (!unmanaged.isEmpty()) stack.child(unmanagedList());
        return stack;
    }

    private UiList extensionList() {
        var list = UiList.of("extension-list", "Extensions  (" + extensions.size() + ")").icon("package");
        if (extensions.isEmpty()) {
            list.item(UiList.Item.of("none", "No extensions")
                    .description("Nothing on the classpath carries a META-INF/mindconnect/extension.json."));
            return list;
        }
        for (ExtensionService.Status status : extensions) {
            list.item(item(status, reports.get(status.id().value()), inBrandNamespace));
        }
        return list;
    }

    private static UiList.Item item(ExtensionService.Status status, ContributionChecker.Report report,
                                    boolean inBrandNamespace) {
        ExtensionManifest manifest = status.manifest();
        String id = status.id().value();
        String heading = manifest.name() + " " + manifest.version() + (status.enabled() ? "" : "  (off)")
                + (report != null && !report.allFound() ? "  ⚠ not everything declared was found" : "");
        var item = UiList.Item.of("extension-" + id, "")
                .content(details(status, report))
                .collapsible(heading, false, "extension-" + id + "-sum");
        if (status.canDecideHere()) {
            if (status.enabled()) {
                item.action(UiAction.secondary("disable", "Switch off").icon("power")
                        .confirm("Switch '" + manifest.name() + "' off for this namespace? Its tools and screens disappear here.")
                        .dispatch("POST", API + "/" + id + "/disable"));
            } else {
                item.action(UiAction.primary("enable", "Switch on").icon("power")
                        .dispatch("POST", API + "/" + id + "/enable"));
            }
        }
        if (status.decision().isPresent()) {
            item.action(UiAction.secondary("reset", "Back to default").icon("refresh")
                    .dispatch("POST", API + "/" + id + "/reset"));
        }
        if (inBrandNamespace && status.origin() != ExtensionService.Origin.OPERATOR) {
            String brand = status.brand().orElse("brand");
            boolean brandOn = status.brandDecision().map(d -> d.enabled()).orElse(manifest.isEnabledByDefault());
            boolean locked = status.brandDecision().map(d -> d.locked()).orElse(false);
            if (brandOn) {
                item.action(UiAction.secondary("brand-disable", "Brand " + brand + ": switch off").icon("power")
                        .confirm("Switch '" + manifest.name() + "' off for every namespace of the brand " + brand + "?")
                        .dispatch("POST", API + "/" + id + "/brand/disable"));
            } else {
                item.action(UiAction.secondary("brand-enable", "Brand " + brand + ": switch on").icon("power")
                        .dispatch("POST", API + "/" + id + "/brand/enable"));
            }
            item.action(UiAction.secondary(locked ? "brand-unlock" : "brand-lock",
                            locked ? "Brand " + brand + ": unlock" : "Brand " + brand + ": lock")
                    .icon(locked ? "unlock" : "lock")
                    .dispatch("POST", API + "/" + id + (locked ? "/brand/unlock" : "/brand/lock")));
            if (status.brandDecision().isPresent()) {
                item.action(UiAction.secondary("brand-reset", "Brand " + brand + ": back to default").icon("refresh")
                        .dispatch("POST", API + "/" + id + "/brand/reset"));
            }
        }
        return item;
    }

    /** Two tables: what the extension is, and what it brings. */
    private static UiStack details(ExtensionService.Status status, ContributionChecker.Report report) {
        String id = status.id().value();
        UiStack stack = UiStack.of("extension-" + id + "-details");
        stack.child(rows("extension-" + id + "-about", "About", about(status)));
        List<String[]> brings = brings(status.manifest().contributes(), status.manifest().permissions(), report);
        if (brings.isEmpty()) {
            stack.child(UiText.of("extension-" + id + "-brings-none", "Brings: nothing declared."));
        } else {
            stack.child(rows("extension-" + id + "-brings", "Brings", brings));
        }
        return stack;
    }

    private static UiTable rows(String id, String title, List<String[]> rows) {
        var table = UiTable.of(id, title).stackOnMobile(true)
                .column(UiColumn.text("what", ""))
                .column(UiColumn.text("value", ""));
        int i = 0;
        for (String[] row : rows) {
            table.row(Map.of("id", "r" + (i++), "what", row[0], "value", row[1]));
        }
        return table;
    }

    private static List<String[]> about(ExtensionService.Status status) {
        ExtensionManifest manifest = status.manifest();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Id", status.id().value()});
        if (!manifest.vendor().label().isEmpty()) rows.add(new String[]{"Vendor", vendor(manifest.vendor())});
        if (manifest.description() != null && !manifest.description().isBlank()) {
            rows.add(new String[]{"Description", manifest.description()});
        }
        rows.add(new String[]{"Runtime", manifest.runtime().wireName() + " · from " + status.extension().origin()});
        rows.add(new String[]{"In this namespace", state(status)});
        status.brand().ifPresent(brand -> rows.add(new String[]{"For the brand " + brand, brandState(status)}));
        if (manifest.requires().mindconnect() != null) {
            rows.add(new String[]{"Requires", "mindconnect " + manifest.requires().mindconnect()});
        }
        if (!manifest.requires().extensions().isEmpty()) {
            rows.add(new String[]{"Requires extensions", join(manifest.requires().extensions().stream()
                    .map(d -> d.id().value() + (d.isOptional() ? " (optional)" : "")).toList())});
        }
        return rows;
    }

    /** The contributions, each with the classpath's verdict where one was taken. */
    private static List<String[]> brings(ExtensionManifest.Contributes brings, List<String> permissions,
                                         ContributionChecker.Report report) {
        List<String[]> rows = new ArrayList<>();
        if (!permissions.isEmpty()) rows.add(new String[]{"Permissions", join(permissions)});
        if (!brings.tools().names().isEmpty()) {
            rows.add(new String[]{"Tools", checked(brings.tools().names(), report == null ? null : report.tools())});
        }
        if (!brings.tools().providers().isEmpty()) {
            rows.add(new String[]{"Tool providers", checked(brings.tools().providers(), report == null ? null : report.providers())});
        }
        if (!brings.features().isEmpty()) {
            rows.add(new String[]{"Runtime features", checked(brings.features(), report == null ? null : report.features())});
        }
        if (!brings.content().agents().isEmpty()) rows.add(new String[]{"Agents", join(brings.content().agents())});
        if (!brings.content().skills().isEmpty()) rows.add(new String[]{"Skills", join(brings.content().skills())});
        if (!brings.content().workflows().isEmpty()) rows.add(new String[]{"Workflows", join(brings.content().workflows())});
        if (!brings.ui().menu().isEmpty()) {
            rows.add(new String[]{"Menu", join(brings.ui().menu().stream().map(ExtensionListComponent::menuEntry).toList())});
        }
        if (!brings.ui().routes().isEmpty()) {
            rows.add(new String[]{"Routes", join(brings.ui().routes().stream()
                    .map(r -> r.path() + (r.roles().isEmpty() ? "" : " (" + String.join(", ", r.roles()) + ")")).toList())});
        }
        if (!brings.ui().assets().isEmpty()) rows.add(new String[]{"Assets", join(brings.ui().assets())});
        if (!brings.rest().isEmpty()) rows.add(new String[]{"REST", join(brings.rest())});
        if (!brings.decorates().isEmpty()) rows.add(new String[]{"Decorates", join(brings.decorates())});
        if (!brings.replaces().isEmpty()) rows.add(new String[]{"Replaces", join(brings.replaces())});
        if (!brings.persistence().isEmpty()) {
            rows.add(new String[]{"Persistence", (brings.persistence().schema() == null ? "" : "schema " + brings.persistence().schema())
                    + (brings.persistence().migrations() == null ? "" : " · migrations " + brings.persistence().migrations())});
        }
        return rows;
    }

    /** The declared items, each followed by its verdict when there is one: {@code acme_* ✓ 3 tools, crm_export ✗ no tool …}. */
    private static String checked(List<String> declared, List<ContributionChecker.Check> checks) {
        if (checks == null || checks.isEmpty()) return join(declared);
        List<String> parts = new ArrayList<>();
        for (ContributionChecker.Check check : checks) {
            parts.add(check.item() + (check.found() ? " ✓ " : " ✗ ") + check.detail());
        }
        return join(parts);
    }

    private static String menuEntry(ExtensionManifest.Ui.MenuEntry entry) {
        if (!entry.isRenderable()) return entry.id() + " (declared only)";
        String where = entry.group() == null || entry.group().isBlank() ? "" : " in " + entry.group();
        String who = entry.forUsers() ? ", also for users" : "";
        return entry.label() + " → " + entry.href() + where + who;
    }

    private static String state(ExtensionService.Status status) {
        String on = status.enabled() ? "on" : "off";
        return switch (status.origin()) {
            case OPERATOR -> "off — switched off by the operator (mindconnect.extensions.disabled); nobody here can change that";
            case BRAND_LOCKED -> on + " — decided and locked for the brand " + status.brand().orElse("") + "; this namespace cannot override";
            case NAMESPACE -> {
                var decision = status.decision().orElseThrow();
                String by = decision.changedBy() == null ? "" : " by " + decision.changedBy().value();
                yield on + " — decided here" + by + " at " + decision.changedAt();
            }
            case BRAND -> on + " — as decided for the brand " + status.brand().orElse("") + "; this namespace may override";
            case DEFAULT -> on + " (the manifest's default)";
        };
    }

    private static String brandState(ExtensionService.Status status) {
        if (status.brandDecision().isEmpty()) return "no decision — the namespaces of the brand follow their own and the manifest";
        var decision = status.brandDecision().get();
        String by = decision.changedBy() == null ? "" : " by " + decision.changedBy().value();
        return (decision.enabled() ? "on" : "off") + (decision.locked() ? ", locked" : "") + " — decided" + by
                + " at " + decision.changedAt();
    }

    private static String vendor(ExtensionManifest.Vendor vendor) {
        return vendor.homepage() == null || vendor.homepage().isBlank()
                ? vendor.label() : vendor.label() + " · " + vendor.homepage();
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }

    private UiList problemList() {
        var list = UiList.of("extension-problems", "Problems  (" + problems.size() + ")").icon("alert-triangle");
        int i = 0;
        for (ExtensionRegistry.Problem problem : problems) {
            list.item(UiList.Item.of("problem-" + (i++), problem.id() == null ? "Manifest" : problem.id().value())
                    .description(problem.message()));
        }
        return list;
    }

    private UiList unmanagedList() {
        var list = UiList.of("extension-unmanaged", "Without a manifest  (" + unmanaged.size() + ")").icon("alert-triangle");
        for (ClasspathAudit.UnmanagedJar jar : unmanaged) {
            list.item(UiList.Item.of("unmanaged-" + jar.jar(), jar.jar())
                    .description("brings " + join(jar.providers())
                            + (jar.groupId() == null ? "" : " (" + jar.groupId() + ")")
                            + " — loaded as before, but nothing here can switch it off until it declares itself"));
        }
        return list;
    }
}
