package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiSectionEntry;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin UI for vector stores: templates (the policies — backend, embedding
 * config, ingestion workflow) and the store instances created from them.
 * Uses the same {@link VectorStores} resolution the knowledge tools use, so
 * what the UI shows is exactly what the tools do.
 */
@RestController
@RequestMapping("/admin/vector-stores")
public class VectorStoreUiController {

    private static final String BASE = "/admin/vector-stores";

    private final VectorStores stores;
    private final LlmConfigRepository llmConfigs;
    private final ai.mindconnect.filestore.FileStore fileStore;
    private final ai.mindconnect.agentrest.service.VectorStoreService vectorStoreService;

    public VectorStoreUiController(VectorStores stores, LlmConfigRepository llmConfigs,
                                      ai.mindconnect.filestore.FileStore fileStore,
                                      ai.mindconnect.agentrest.service.VectorStoreService vectorStoreService) {
        this.stores = stores;
        this.llmConfigs = llmConfigs;
        this.fileStore = fileStore;
        this.vectorStoreService = vectorStoreService;
    }

    // ── overview page ──────────────────────────────────────────────────────

    @GetMapping
    public UiPage list() {
        return list("templates");
    }

    /** The overview as tabs; {@code tab} picks which one opens (after an action, the one it happened in). */
    private UiPage list(String tab) {
        UiTable templates = UiTable.of("vs-templates", null)
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("backend", "Backend"))
                .column(UiTable.Column.text("embedding", "Embedding Config"))
                .column(UiTable.Column.text("workflow", "Ingestion Workflow"))
                .column(UiTable.Column.text("description", "Description"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", "/admin/vector-stores/templates/{id}/edit"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this template? Existing stores keep their copied settings.")
                        .dispatch("DELETE", "/admin/vector-stores/templates/{id}"));
        for (VectorStoreTemplate t : stores.templates()) {
            templates.row(Map.of(
                    "id", t.name(),
                    "name", VectorStores.DEFAULT_TEMPLATE.equals(t.name()) ? t.name() + " (built-in)" : t.name(),
                    "backend", t.backend(),
                    "embedding", t.embeddingConfig(),
                    "workflow", t.ingestionWorkflow() == null ? "" : t.ingestionWorkflow(),
                    "description", t.metadata().getOrDefault("description", "")));
        }

        UiTable instances = UiTable.of("vs-instances", null)
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("template", "Template"))
                .column(UiTable.Column.text("backend", "Backend"))
                .column(UiTable.Column.text("scope", "Scope"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.secondary("view", "View").icon("show")
                        .dispatch("GET", "/admin/vector-stores/stores/{id}"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this store registration? (Data files stay on the backend.)")
                        .dispatch("DELETE", "/admin/vector-stores/stores/{id}"));
        Set<String> registered = new LinkedHashSet<>();
        for (VectorStoreInstance i : stores.registry().instances()) {
            registered.add(i.name());
            instances.row(Map.of(
                    "id", i.name(),
                    "name", i.name(),
                    "template", i.templateName(),
                    "backend", i.backend(),
                    "scope", i.scope() + (i.scopeRef() == null ? "" : " (" + shortRef(i.scopeRef()) + ")"),
                    "chunks", chunkCount(i)));
        }
        // Stores that physically exist but were never registered (pre-template
        // era, or created outside the tools) — shown as implicit defaults.
        for (String discovered : stores.discoverStores(
                stores.templates().get(0).backend(), Map.of())) {
            if (registered.contains(discovered)) continue;
            VectorStoreInstance implicit = stores.settingsFor(discovered);
            instances.row(Map.of(
                    "id", discovered,
                    "name", discovered + " (unregistered)",
                    "template", VectorStores.DEFAULT_TEMPLATE,
                    "backend", implicit.backend(),
                    "scope", "GLOBAL",
                    "chunks", chunkCount(implicit)));
        }

        // The file store (Files API): raw uploads addressed by id, independent
        // of any store — attach them to a chat via POST /api/sessions/{id}/files.
        UiTable files = UiTable.of("vs-files-all", null)
                .column(UiTable.Column.text("fid", "Id"))
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("size", "Size"))
                .column(UiTable.Column.text("created", "Uploaded"))
                .rowAction(UiAction.secondary("download", "Download").icon("download")
                        .onClick(ai.mindconnect.ui.model.UiTrigger.openInTab("/api/files/{id}/content")))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this file from the file store? (Already-ingested chunks stay.)")
                        .dispatch("DELETE", "/admin/vector-stores/files/{id}"));
        for (ai.mindconnect.filestore.StoredFile f : fileStore.list()) {
            files.row(Map.of(
                    "id", f.id(),
                    "fid", f.id(),
                    "name", f.name(),
                    "type", f.contentType() == null ? "" : f.contentType(),
                    "size", readableSize(f.size()),
                    "created", f.createdAt() == null ? "" : f.createdAt().toString()
                            .replace("T", " ").substring(0, 16)));
        }
        var upload = ai.mindconnect.ui.model.UiUpload
                .of("vs-files-upload", "Upload to file store")
                .multiple()
                .hint("Stores the file and returns its id — attach to a chat via "
                        + "POST /api/sessions/{sessionId}/files, or ingest from a store page")
                .uploadTo("/admin/vector-stores/files/upload");

        UiStack filesTab = UiStack.of("vs-files-tab").gap(20)
                .child(files).child(upload);

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
        UiList header = UiList.of("vs-header", "Vector Stores").icon("database");

        // The action belongs to the tab you are on, and the controller knows
        // which that is — so it sits in the page header and changes with the
        // tab, rather than each table keeping a header bar of its own that
        // holds nothing but a button.
        String active = tab == null ? "templates" : tab;
        if ("stores".equals(active)) {
            header.action(UiAction.primary("new-store", "New Store").icon("add")
                    .dispatch("GET", "/admin/vector-stores/stores/new"));
        } else if ("templates".equals(active)) {
            header.action(UiAction.primary("new-template", "New Template").icon("add")
                    .dispatch("GET", "/admin/vector-stores/templates/new"));
        }

        // One tab per concern, each carrying its icon. The tables carry no
        // titles: the tab label names them and the header above names the
        // screen.
        UiSection tabs = UiSection.of("vs-page", null);
        tabs.getSections().add(UiSectionEntry.of("templates", "Templates", templates).icon("database"));
        tabs.getSections().add(UiSectionEntry.of("stores", "Stores", instances).icon("server"));
        tabs.getSections().add(UiSectionEntry.of("files", "Files", filesTab).icon("file"));
        tabs.initialSection(tab);

        UiStack page = UiStack.of("vs-shell").child(header).child(tabs);
        return UiPage.of(BASE, page);
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UiPage uploadFiles(@org.springframework.web.bind.annotation.RequestParam("vs-files-upload")
                              List<org.springframework.web.multipart.MultipartFile> uploads) {
        for (var upload : uploads) {
            try (var content = upload.getInputStream()) {
                fileStore.save(upload.getOriginalFilename(), upload.getContentType(), content);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Upload failed: " + e.getMessage(), e);
            }
        }
        return list("files");
    }

    @DeleteMapping("/files/{id}")
    public UiPage deleteStoredFile(@PathVariable String id) throws java.io.IOException {
        fileStore.delete(id);
        return list("files");
    }

    private String chunkCount(VectorStoreInstance instance) {
        try {
            return String.valueOf(stores.openWith(instance).chunkCount());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String shortRef(String ref) {
        return ref.length() > 12 ? ref.substring(0, 12) + "…" : ref;
    }

    // ── template form ──────────────────────────────────────────────────────

    @GetMapping("/templates/new")
    public UiPage newTemplate() {
        return templateForm(null);
    }

    @GetMapping("/templates/{name}/edit")
    public UiPage editTemplate(@PathVariable String name) {
        return templateForm(stores.registry().template(name).orElse(null));
    }

    private UiPage templateForm(VectorStoreTemplate t) {
        boolean isNew = t == null;
        List<UiField.Option> backends = List.of(
                UiField.Option.of("memory", "memory"), UiField.Option.of("pgvector", "pgvector"));
        List<UiField.Option> embeddingOptions = llmConfigs.findAll().stream()
                .map(c -> UiField.Option.of(c.name(), c.name())).toList();
        UiForm form = UiForm.of("vs-template-form", isNew ? "New Template" : "Edit Template: " + t.name())
                .field(UiField.text("name", "Name", isNew ? null : t.name()).asEditable()
                        .hint("Unique template name, e.g. knowledge or chat-uploads"))
                .field(UiField.select("backend", "Backend", isNew ? "memory" : t.backend(), backends)
                        .asEditable()
                        // Switching the backend swaps the backend-config group below.
                        .onChange(ai.mindconnect.ui.model.UiTrigger.api("POST",
                                BASE + "/templates/backend-fields", "vs-template-form")))
                .field(UiField.select("embeddingConfig", "Embedding Config",
                        isNew ? "embeddings" : t.embeddingConfig(), embeddingOptions)
                        .asEditable()
                        .hint("LlmConfig naming the embedding model — fixed per store at creation; "
                                + "changing it later never affects existing stores"))
                .field(UiField.text("ingestionWorkflow", "Ingestion Workflow",
                        isNew ? "file-ingestion" : t.ingestionWorkflow()).asEditable()
                        .hint("Workflow started by 'Ingest file…' on stores of this template"))
                .field(UiField.text("description", "Description",
                        isNew ? null : t.metadata().get("description")).asEditable());
        form.content(backendConfigGroup(isNew ? "memory" : t.backend(),
                isNew ? Map.of() : t.backendConfig()));
        form.action(UiAction.primary("save", "Save").icon("save")
                        .dispatch("POST", "/admin/vector-stores/templates", "vs-template-form"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", "/admin/vector-stores"))
                .link(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + (isNew ? "/templates/new" : "/templates/" + t.name() + "/edit"), form);
    }

    /** The backend-specific settings, swapped in place when the dropdown changes. */
    private static ai.mindconnect.ui.model.UiFieldGroup backendConfigGroup(String backend,
                                                                           Map<String, String> config) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("vs-backend-cfg",
                "pgvector".equals(backend) ? "Backend: pgvector" : "Backend: memory");
        if ("pgvector".equals(backend)) {
            group.field(UiField.text("url", "JDBC URL", config.get("url")).asEditable()
                    .hint("e.g. jdbc:postgresql://localhost:5433/postgres"));
            group.field(UiField.text("user", "DB User", config.get("user")).asEditable());
            group.field(UiField.password("password", "DB Password", config.get("password")).asEditable());
        } else {
            group.field(UiField.text("dir", "Directory", config.get("dir")).asEditable()
                    .hint("Optional override; default is mindconnect.vector-store.dir (data/vector-stores)"));
        }
        return group;
    }

    /** Backend dropdown changed: re-render the config group for the new choice. */
    @PostMapping("/templates/backend-fields")
    public ai.mindconnect.ui.model.UiPatch backendFields(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        Map<String, String> current = new LinkedHashMap<>();
        putIfPresent(current, "url", body.str("url"));
        putIfPresent(current, "user", body.str("user"));
        putIfPresent(current, "password", body.str("password"));
        putIfPresent(current, "dir", body.str("dir"));
        return ai.mindconnect.ui.model.UiPatch.of().patch(
                ai.mindconnect.ui.model.UiPatch.Operation.replace("vs-backend-cfg",
                        backendConfigGroup(body.str("backend"), current)));
    }

    @PostMapping("/templates")
    public UiPage saveTemplate(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name");
        if (name == null || name.isBlank() || VectorStores.DEFAULT_TEMPLATE.equals(name)) {
            return list("templates");
        }
        Map<String, String> backendConfig = new LinkedHashMap<>();
        putIfPresent(backendConfig, "url", body.str("url"));
        putIfPresent(backendConfig, "user", body.str("user"));
        putIfPresent(backendConfig, "password", body.str("password"));
        putIfPresent(backendConfig, "dir", body.str("dir"));
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "description", body.str("description"));
        stores.registry().saveTemplate(new VectorStoreTemplate(name.trim(),
                body.str("backend") == null ? "memory" : body.str("backend"),
                backendConfig,
                body.str("embeddingConfig") == null ? "embeddings" : body.str("embeddingConfig"),
                body.str("ingestionWorkflow"), metadata));
        return list("templates");
    }

    @DeleteMapping("/templates/{name}")
    public UiPage deleteTemplate(@PathVariable String name) {
        stores.registry().deleteTemplate(name);
        return list("templates");
    }

    // ── store instances ────────────────────────────────────────────────────

    @GetMapping("/stores/new")
    public UiPage newStore() {
        List<UiField.Option> templateOptions = stores.templates().stream()
                .map(t -> UiField.Option.of(t.name(), t.name())).toList();
        UiForm form = UiForm.of("vs-store-form", "New Store")
                .field(UiField.text("name", "Name", null).asEditable()
                        .hint("Store name, e.g. project-x-knowledge"))
                .field(UiField.select("template", "Template", VectorStores.DEFAULT_TEMPLATE, templateOptions)
                        .asEditable()
                        .hint("The template's settings are copied onto the store at creation"));
        form.action(UiAction.primary("create", "Create").icon("add")
                        .dispatch("POST", "/admin/vector-stores/stores", "vs-store-form"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", "/admin/vector-stores"))
                .link(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + "/stores/new", form);
    }

    @PostMapping("/stores")
    public UiPage createStore(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name");
        if (name != null && !name.isBlank()) {
            stores.open(name.trim(), body.str("template"), VectorStoreInstance.Scope.GLOBAL, null);
        }
        return list("stores");
    }

    @GetMapping("/stores/{name}")
    public UiPage storeDetail(@PathVariable String name) {
        return storeDetail(name, null, null, null);
    }

    private UiPage storeDetail(String name, String lastQuery, UiNode searchResult) {
        return storeDetail(name, lastQuery, searchResult, null);
    }

    private UiPage storeDetail(String name, String lastQuery, UiNode searchResult, String message) {
        VectorStoreInstance instance = stores.settingsFor(name);
        VectorStore store = stores.openWith(instance);

        UiStack page = UiStack.of("vs-detail").gap(16);
        page.child(UiText.of("vs-detail-head", "Store '" + name + "' — template " + instance.templateName()
                + ", backend " + instance.backend() + ", embedding " + instance.embeddingConfig()
                + ", scope " + instance.scope()).withCssClass("wf-run-title"));
        if (message != null) {
            page.child(UiText.of("vs-ingest-result", message).withCssClass("task-card-body"));
        }

        // Drag & drop straight into the store: the file is saved under the
        // tools base dir and pushed through the instance's ingestion workflow.
        if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()) {
            page.child(ai.mindconnect.ui.model.UiUpload
                    .of("vs-upload", "Upload & Ingest")
                    .accept(".docx,.pdf,.md,.txt")
                    .multiple()
                    .hint("Runs workflow '" + instance.ingestionWorkflow() + "' for every uploaded file")
                    .uploadTo(BASE + "/stores/" + name + "/upload"));
        }

        UiTable files = UiTable.of("vs-files", "Files")
                .column(UiTable.Column.text("file", "File"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.danger("delete-file", "Delete").icon("delete")
                        .confirm("Remove this file's chunks from the store?")
                        // File ids contain slashes (upload paths) — they travel
                        // URL-encoded in the query, never in the path.
                        .dispatch("DELETE", "/admin/vector-stores/stores/" + name + "/files?file={id}"));
        store.listFiles().forEach((fileId, count) ->
                files.row(Map.of(
                        "id", java.net.URLEncoder.encode(fileId, java.nio.charset.StandardCharsets.UTF_8),
                        "file", fileId,
                        "chunks", String.valueOf(count))));
        page.child(files);

        UiForm search = UiForm.of("vs-search-form", "Test Search")
                .field(UiField.text("query", "Query", lastQuery).asEditable())
                .field(UiField.number("topK", "Max Results", 5).asEditable()
                        .hint("How many chunks to return (1–50)"))
                .field(UiField.number("minScore", "Min Score %", 0).asEditable()
                        .hint("Hide hits below this cosine similarity (0 = show all)"));
        search.action(UiAction.primary("search", "Search").icon("search")
                .dispatch("POST", "/admin/vector-stores/stores/" + name + "/search", "vs-search-form"));
        page.child(search);
        if (searchResult != null) {
            page.child(searchResult);
        }

        if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()) {
            page.child(UiLink.of("ingest", "/workflow-admin/" + instance.ingestionWorkflow() + "/run",
                    "→ Ingest file… (workflow " + instance.ingestionWorkflow() + ")"));
        }
        page.child(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + "/stores/" + name, page);
    }

    /**
     * Receives uploaded files and hands each to the shared
     * {@link ai.mindconnect.agentrest.service.VectorStoreService} ingestion
     * path — the same one the REST API's ingest endpoint takes (ingestion
     * workflow when the template names one, direct chunking otherwise).
     */
    @PostMapping("/stores/{name}/upload")
    public UiPage upload(@PathVariable String name,
                         @org.springframework.web.bind.annotation.RequestParam("vs-upload")
                         List<org.springframework.web.multipart.MultipartFile> files) {
        StringBuilder message = new StringBuilder();
        for (var file : files) {
            String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
            try (var content = file.getInputStream()) {
                message.append(vectorStoreService.ingestUpload(name, fileName, content)).append('\n');
            } catch (Exception e) {
                message.append(fileName).append(": failed — ").append(e.getMessage()).append('\n');
            }
        }
        return storeDetail(name, null, (UiNode) null, message.toString().stripTrailing());
    }

    @PostMapping("/stores/{name}/search")
    public UiPage search(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String query = body.str("query");
        if (query == null || query.isBlank()) {
            return storeDetail(name, null, (UiNode) null);
        }
        int topK = Math.max(1, Math.min(body.num("topK", 5), 50));
        double minScore = Math.max(0, Math.min(body.num("minScore", 0), 100)) / 100.0;
        UiNode result;
        try {
            result = hitsTable(vectorStoreService.search(name, query, topK, minScore));
        } catch (RuntimeException e) {
            result = UiText.of("vs-search-result", "Search failed: " + e.getMessage())
                    .withCssClass("task-card-body");
        }
        return storeDetail(name, query, result);
    }

    /** Search hits as a table: score, provenance, every metadata key, text. */
    private static UiNode hitsTable(List<ai.mindconnect.agentrest.service.VectorStoreService.Hit> hits) {
        if (hits.isEmpty()) {
            return UiText.of("vs-search-result", "No results (store empty, or all hits below Min Score).");
        }
        // Metadata keys vary per chunk — collect the union for the columns.
        Set<String> metaKeys = new java.util.TreeSet<>();
        hits.forEach(h -> metaKeys.addAll(h.metadata().keySet()));

        UiTable table = UiTable.of("vs-search-result", "Results (" + hits.size() + ")")
                .column(UiTable.Column.text("rank", "#"))
                .column(UiTable.Column.text("score", "Score"))
                .column(UiTable.Column.text("chunk", "Chunk"));
        for (String key : metaKeys) {
            table.column(UiTable.Column.text("m-" + key, capitalize(key)));
        }
        table.column(UiTable.Column.text("text", "Text"));

        int rank = 1;
        for (ai.mindconnect.agentrest.service.VectorStoreService.Hit hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", hit.id());
            row.put("rank", String.valueOf(rank++));
            row.put("score", String.format("%.3f", hit.score()));
            row.put("chunk", hit.fileId() + " #" + hit.ordinal());
            for (String key : metaKeys) {
                row.put("m-" + key, hit.metadata().getOrDefault(key, ""));
            }
            row.put("text", firstChars(hit.text()));
            table.row(row);
        }
        return table;
    }

    private static String capitalize(String key) {
        return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    @DeleteMapping("/stores/{name}/files")
    public UiPage deleteFile(@PathVariable String name,
                             @org.springframework.web.bind.annotation.RequestParam("file") String fileId) {
        stores.openWith(stores.settingsFor(name)).deleteFile(fileId);
        return storeDetail(name, null, (UiNode) null);
    }

    @DeleteMapping("/stores/{name}")
    public UiPage deleteStore(@PathVariable String name) {
        stores.registry().deleteInstance(name);
        return list("stores");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String firstChars(String text) {
        String stripped = text.strip().replace('\n', ' ');
        return stripped.length() > 160 ? stripped.substring(0, 160) + "…" : stripped;
    }
}
