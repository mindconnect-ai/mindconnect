package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.FileUsageDialogComponent;
import ai.mindconnect.agentrest.service.FileUsageService.FileUsage;
import ai.mindconnect.agentrest.service.VectorStoreContents.StoreSummary;
import ai.mindconnect.agentrest.service.VectorStoreService.IndexInfo;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiSectionEntry;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiUpload;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The vector stores overview at {@code /admin/vector-stores}: templates,
 * indexes, stores and the caller's stored files, one tab each.
 */
public final class VectorStoreOverviewPage extends AdminPage {

    public static final String HEADER_ID = "vs-header";
    private static final String BASE = "/admin/vector-stores";

    private final List<VectorStoreTemplate> templates;
    private final List<IndexInfo> indexes;
    private final String indexesUnavailable;
    private final List<StoreSummary> stores;
    private final List<StoredFile> files;
    private final FileUsage usages;
    private final String tab;

    /**
     * @param indexes            every index the namespace can use; ignored when they could not be listed
     * @param indexesUnavailable why the indexes could not be listed; {@code null} when they could
     * @param stores             the stores the caller may see
     * @param files              the stored files the caller may read
     * @param tab                the tab that opens — after an action, the one it happened in
     */
    public VectorStoreOverviewPage(List<VectorStoreTemplate> templates, List<IndexInfo> indexes,
                                   String indexesUnavailable, List<StoreSummary> stores, List<StoredFile> files,
                                   FileUsage usages, String tab) {
        this.templates = templates;
        this.indexes = indexes;
        this.indexesUnavailable = indexesUnavailable;
        this.stores = stores;
        this.files = files;
        this.usages = usages;
        this.tab = tab;
    }

    @Override
    public UiPage render() {
        UiTable templateTable = templatesTable();
        UiTable instances = storesTable();
        UiStack filesTab = filesTab();

        // The page's header, and it is a UiList with no items on purpose.
        //
        // This screen needs what every other one has — an icon, a title and
        // the primary action in one bar — above a set of tabs. UiSection can
        // only carry a title, which it renders as a bare <h2>, and that is
        // exactly what made this page look like it came from somewhere else.
        // A header-only list renders the identical bar to the one on Agents,
        // down to the padding and the hairline, because it *is* that bar.
        //
        // The honest fix is a UiSection that takes an icon and actions; then
        // this is one node instead of two and the empty <ul> goes away. Until
        // then, borrowing the right-looking header beats hand-building a
        // lookalike that drifts from it.
        UiList header = header(tab);

        // One tab per concern, each carrying its icon. The tables carry no
        // titles: the tab label names them and the header above names the
        // screen. Switching a tab is a client-side panel swap, but the
        // header's action belongs to the tab you are on — so each click also
        // asks the server for the matching header (a REPLACE patch on
        // vs-header). Without this, the button rendered for the initial tab
        // simply stayed, and the Stores tab had no way to create a store.
        UiSection tabs = UiSection.of("vs-page", null);
        tabs.getSections().add(UiSectionEntry.of("templates", "Templates", templateTable).icon("database")
                .onClick(UiTrigger.api("GET", BASE + "/header?tab=templates")));
        tabs.getSections().add(UiSectionEntry.of("indexes", "Indexes", indexesTable()).icon("server")
                .onClick(UiTrigger.api("GET", BASE + "/header?tab=indexes")));
        tabs.getSections().add(UiSectionEntry.of("stores", "Stores", instances).icon("server")
                .onClick(UiTrigger.api("GET", BASE + "/header?tab=stores")));
        tabs.getSections().add(UiSectionEntry.of("files", "Files", filesTab).icon("file")
                .onClick(UiTrigger.api("GET", BASE + "/header?tab=files")));
        tabs.initialSection(tab);

        UiStack page = UiStack.of("vs-shell").child(header).child(tabs);
        return UiPage.of(BASE, page);
    }

    /**
     * The page's header, and it is a UiList with no items on purpose.
     * This screen needs what every other one has — an icon, a title and
     * the primary action in one bar — above a set of tabs, and a
     * header-only list renders the identical bar to the one on Agents.
     * The action follows the active tab; Files has none (its uploads
     * happen inside the tab).
     */
    public static UiList header(String tab) {
        UiList header = UiList.of(HEADER_ID, "Vector Stores").icon("database");
        String active = tab == null ? "templates" : tab;
        if ("stores".equals(active)) {
            header.action(UiAction.primary("new-store", "New Store").icon("add")
                    .dispatch("GET", "/admin/vector-stores/stores/new"));
        } else if ("templates".equals(active)) {
            header.action(UiAction.primary("new-template", "New Template").icon("add")
                    .dispatch("GET", "/admin/vector-stores/templates/new"));
        } else if ("indexes".equals(active)) {
            header.action(UiAction.primary("new-index", "New Index").icon("add")
                    .dispatch("GET", "/admin/vector-stores/indexes/new"));
        }
        return header;
    }

    private UiTable templatesTable() {
        UiTable table = UiTable.of("vs-templates", null).stackOnMobile(true)
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("embedding", "Embedding Config"))
                .column(UiTable.Column.text("index", "Index"))
                .column(UiTable.Column.text("workflow", "Ingestion Workflow"))
                .column(UiTable.Column.text("description", "Description"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", "/admin/vector-stores/templates/{id}/edit"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this template? Existing stores keep their copied settings.")
                        .dispatch("DELETE", "/admin/vector-stores/templates/{id}"));
        for (VectorStoreTemplate t : templates) {
            table.row(Map.of(
                    "id", t.name(),
                    "name", VectorStores.DEFAULT_TEMPLATE.equals(t.name()) ? t.name() + " (built-in)" : t.name(),
                    "embedding", t.embeddingConfig(),
                    "index", t.effectiveIndex(),
                    "workflow", t.ingestionWorkflow() == null ? "" : t.ingestionWorkflow(),
                    "description", t.metadata().getOrDefault("description", "")));
        }
        return table;
    }

    private UiTable storesTable() {
        UiTable table = UiTable.of("vs-instances", null).stackOnMobile(true)
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("template", "Template"))
                .column(UiTable.Column.text("scope", "Scope"))
                .column(UiTable.Column.text("index", "Index"))
                .column(UiTable.Column.text("entries", "Entries"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.secondary("view", "View").icon("show")
                        .dispatch("GET", "/admin/vector-stores/stores/{id}"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this store? Its documents go with it; stored files stay searchable "
                                + "in the other stores and chats that list them.")
                        .dispatch("DELETE", "/admin/vector-stores/stores/{id}"));
        for (StoreSummary summary : stores) {
            VectorStoreInstance i = summary.instance();
            table.row(Map.of(
                    "id", i.name(),
                    "name", i.name(),
                    "template", i.templateName(),
                    "scope", i.scope() + (i.scopeRef() == null ? "" : " (" + shortRef(i.scopeRef()) + ")"),
                    "index", summary.index(),
                    "entries", summary.entries().isPresent() ? String.valueOf(summary.entries().getAsLong()) : "?",
                    "chunks", summary.chunks().isPresent() ? String.valueOf(summary.chunks().getAsLong()) : "?"));
        }
        return table;
    }

    /**
     * The file store (Files API): raw uploads addressed by id, independent
     * of any store — attach them to a chat via POST /api/sessions/{id}/files.
     */
    private UiStack filesTab() {
        UiTable table = UiTable.of("vs-files-all", null).stackOnMobile(true)
                .column(UiTable.Column.text("fid", "Id"))
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("size", "Size"))
                .column(UiTable.Column.text("created", "Uploaded"))
                .column(UiTable.Column.text("used", "Used in"))
                .rowAction(UiAction.secondary("usage", "Used in…").icon("link")
                        .onClick(UiTrigger.api("GET", BASE + "/files/{id}/usage")))
                .rowAction(UiAction.secondary("download", "Download").icon("download")
                        .onClick(UiTrigger.openInTab(BASE + "/files/{id}/content")))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this file from the file store? The chats and stores that list it keep "
                                + "their entry until it is removed there.")
                        .dispatch("DELETE", "/admin/vector-stores/files/{id}"));
        for (StoredFile f : files) {
            table.row(Map.of(
                    "id", f.id().value(),
                    "fid", f.id().value(),
                    "name", f.name(),
                    "type", f.contentType() == null ? "" : f.contentType(),
                    "size", FileUsageDialogComponent.readableSize(f.size()),
                    "created", f.createdAt() == null ? "" : f.createdAt().toString()
                            .replace("T", " ").substring(0, 16),
                    "used", usedIn(f.id().value())));
        }
        var upload = UiUpload
                .of("vs-files-upload", "Upload to file store")
                .multiple()
                .hint("Stores the file and returns its id — attach to a chat via "
                        + "POST /api/sessions/{sessionId}/files, or ingest from a store page")
                .uploadTo("/admin/vector-stores/files/upload");

        return UiStack.of("vs-files-tab").gap(20)
                .child(table).child(upload);
    }

    /** Every index the namespace can use: where it lives, which templates name it. */
    private UiNode indexesTable() {
        if (indexesUnavailable != null) {
            return UiText.of("vs-indexes", "The indexes cannot be listed: " + indexesUnavailable)
                    .withCssClass("task-card-body");
        }
        UiTable table = UiTable.of("vs-indexes", null).stackOnMobile(true)
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("where", "Where"))
                .column(UiTable.Column.text("templates", "Templates"))
                .column(UiTable.Column.text("description", "Description"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", BASE + "/indexes/{id}/edit"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this index definition? Stores naming it can no longer be searched; a "
                                + "built-in index goes back to the server's settings. The chunks stay where they are.")
                        .dispatch("DELETE", BASE + "/indexes/{id}"));
        for (IndexInfo info : indexes) {
            IndexDefinition index = info.definition();
            String usedBy = templates.stream().filter(t -> index.name().equals(t.effectiveIndex()))
                    .map(VectorStoreTemplate::name).collect(Collectors.joining(", "));
            table.row(Map.of(
                    "id", index.name(),
                    "name", index.name() + (info.builtIn() ? " (built-in)" : ""),
                    "where", info.location(),
                    "templates", usedBy.isEmpty() ? "—" : usedBy,
                    "description", index.description() == null ? "" : index.description()));
        }
        return table;
    }

    /** "2 chats · 1 store", or a dash for a file nothing lists. */
    private String usedIn(String fileId) {
        int c = usages.chatsOf(fileId).size();
        int s = usages.storesOf(fileId).size();
        if (c == 0 && s == 0) return "—";
        List<String> parts = new ArrayList<>();
        if (c > 0) parts.add(c + (c == 1 ? " chat" : " chats"));
        if (s > 0) parts.add(s + (s == 1 ? " store" : " stores"));
        return String.join(" · ", parts);
    }

    private static String shortRef(String ref) {
        return ref.length() > 12 ? ref.substring(0, 12) + "…" : ref;
    }
}
