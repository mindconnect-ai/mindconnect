package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiSectionEntry;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiUpload;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;

/**
 * The store: its settings, an upload when it has an ingestion workflow, and
 * three tabs — the entries it lists, the chunks they were cut into, and a
 * test search. Entries and chunks search and page on the server; the
 * search field and the page buttons fetch just their table again.
 */
public final class VectorStoreDetailPage extends AdminPage {

    private static final String BASE = "/admin/vector-stores";

    private final String name;
    private final VectorStoreInstance instance;
    private final String index;
    private final String location;
    private final UiNode entries;
    private final UiNode chunks;
    private String tab;
    private String lastQuery;
    private UiNode searchResult;
    private String message;

    /**
     * @param index    the index the store's chunks are kept in
     * @param location where that index is, or why that cannot be said
     * @param entries  the entries tab's table, or what stands in its place
     * @param chunks   the chunks tab's table, or what stands in its place
     */
    public VectorStoreDetailPage(String name, VectorStoreInstance instance, String index, String location,
                                 UiNode entries, UiNode chunks) {
        this.name = name;
        this.instance = instance;
        this.index = index;
        this.location = location;
        this.entries = entries;
        this.chunks = chunks;
    }

    /** The tab that opens; the entries tab when none is named. */
    public VectorStoreDetailPage opening(String tab) {
        this.tab = tab;
        return this;
    }

    /** The test search that was just run: its query stays in the field, its result shows under it. */
    public VectorStoreDetailPage searched(String lastQuery, UiNode searchResult) {
        this.lastQuery = lastQuery;
        this.searchResult = searchResult;
        return this;
    }

    /** What an upload came to, shown above the tabs. */
    public VectorStoreDetailPage saying(String message) {
        this.message = message;
        return this;
    }

    @Override
    public UiPage render() {
        UiStack page = UiStack.of("vs-detail").gap(16);
        // The same header bar as every other detail screen: icon, the store's
        // name as the title, its settings as a quiet meta line underneath.
        UiList header = UiList.of("vs-detail-head", name).icon("server");
        header.action(UiAction.secondary("back", "All vector stores").icon("back")
                .onClick(UiTrigger.go(BASE)));
        page.child(header);
        page.child(UiText.of("vs-detail-meta", "template " + instance.templateName()
                + " · embedding " + instance.embeddingConfig()
                + " · scope " + instance.scope()).withCssClass("wf-run-meta"));
        page.child(UiText.of("vs-detail-index", "index " + index + " · " + location).withCssClass("wf-run-meta"));
        if (message != null) {
            page.child(UiText.of("vs-ingest-result", message).withCssClass("task-card-body"));
        }

        // Drag & drop straight into the store: the file is saved under the
        // tools base dir and pushed through the instance's ingestion workflow.
        if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()) {
            page.child(UiUpload
                    .of("vs-upload", "Upload & Ingest")
                    .accept(".docx,.pdf,.md,.txt")
                    .multiple()
                    .hint("Runs workflow '" + instance.ingestionWorkflow() + "' for every uploaded file")
                    .uploadTo(BASE + "/stores/" + name + "/upload"));
        }

        UiForm search = UiForm.of("vs-search-form", null)
                .field(UiField.text("query", "Query", lastQuery).asEditable())
                .field(UiField.number("topK", "Max Results", 5).asEditable()
                        .hint("How many chunks to return (1–50)"))
                .field(UiField.number("minScore", "Min Score %", 0).asEditable()
                        .hint("Hide hits below this cosine similarity (0 = show all)"));
        search.action(UiAction.primary("search", "Search").icon("search")
                .dispatch("POST", "/admin/vector-stores/stores/" + name + "/search", "vs-search-form"));
        UiStack searchTab = UiStack.of("vs-search-tab").gap(16).child(search);
        if (searchResult != null) {
            searchTab.child(searchResult);
        }
        if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()) {
            searchTab.child(UiLink.of("ingest", "/workflow-admin/" + instance.ingestionWorkflow() + "/run",
                    "→ Ingest file… (workflow " + instance.ingestionWorkflow() + ")"));
        }

        UiSection tabs = UiSection.of("vs-store-tabs", null);
        tabs.getSections().add(UiSectionEntry.of("entries", "Entries", entries).icon("file"));
        tabs.getSections().add(UiSectionEntry.of("chunks", "Chunks", chunks).icon("list"));
        tabs.getSections().add(UiSectionEntry.of("search", "Search", searchTab).icon("search"));
        tabs.initialSection(tab == null || tab.isBlank() ? "entries" : tab);
        page.child(tabs);
        return UiPage.of(BASE + "/stores/" + name, page);
    }
}
