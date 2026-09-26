package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.common.StaleVersionException;
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
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStore;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin UI for vector stores: templates (the policies — backend, embedding
 * config, ingestion workflow) and the store instances created from them.
 * Uses the same {@link VectorStores} resolution the knowledge tools use, so
 * what the UI shows is exactly what the tools do.
 *
 * <p>Templates and knowledge bases are shared configuration. A chat's upload
 * store is its user's alone ({@link ai.mindconnect.agentrest.auth.VectorStoreAccess}):
 * another user neither sees it listed nor opens, searches, fills or deletes
 * it. The files tab shows what the Files API gives the signed-in user, and
 * only a file's uploader deletes it.
 */
@RestController
@RequestMapping("/admin/vector-stores")
public class VectorStoreUiController {

    private static final String BASE = "/admin/vector-stores";

    private final VectorStores stores;
    /** Where the request works — every store call names the namespace. */
    private final ai.mindconnect.agent.ScopeSupplier scope;
    private final LlmConfigRepository llmConfigs;
    private final ai.mindconnect.filestore.FileStore fileStore;
    private final ai.mindconnect.agentrest.service.VectorStoreService vectorStoreService;
    private final ai.mindconnect.agentrest.auth.CurrentUsers currentUsers;
    private final ai.mindconnect.agentrest.auth.VectorStoreAccess storeAccess;
    private final ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions;

    public VectorStoreUiController(VectorStores stores, LlmConfigRepository llmConfigs,
                                      ai.mindconnect.filestore.FileStore fileStore,
                                      ai.mindconnect.agentrest.service.VectorStoreService vectorStoreService,
                                      ai.mindconnect.agentrest.auth.CurrentUsers currentUsers,
                                      ai.mindconnect.agentrest.auth.VectorStoreAccess storeAccess,
                                      ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions,
                                      ai.mindconnect.agent.ScopeSupplier scope) {
        this.sessions = sessions;
        this.stores = stores;
        this.scope = scope;
        this.llmConfigs = llmConfigs;
        this.fileStore = fileStore;
        this.vectorStoreService = vectorStoreService;
        this.currentUsers = currentUsers;
        this.storeAccess = storeAccess;
    }

    // ── overview page ──────────────────────────────────────────────────────

    @GetMapping
    public UiPage list() {
        return list("templates");
    }

    /** The overview as tabs; {@code tab} picks which one opens (after an action, the one it happened in). */
    private UiPage list(String tab) {
        UiTable templates = UiTable.of("vs-templates", null).stackOnMobile(true)
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
        for (VectorStoreTemplate t : stores.templates(scope.namespace())) {
            templates.row(Map.of(
                    "id", t.name(),
                    "name", VectorStores.DEFAULT_TEMPLATE.equals(t.name()) ? t.name() + " (built-in)" : t.name(),
                    "embedding", t.embeddingConfig(),
                    "index", t.effectiveIndex(),
                    "workflow", t.ingestionWorkflow() == null ? "" : t.ingestionWorkflow(),
                    "description", t.metadata().getOrDefault("description", "")));
        }

        UiTable instances = UiTable.of("vs-instances", null).stackOnMobile(true)
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
        ai.mindconnect.agent.UserId caller = currentUsers.require();
        // Opening the index first brings in what the namespace kept before 0.9.
        stores.index(scope.namespace());
        for (VectorStoreInstance i : stores.registry(scope.namespace()).instances()) {
            // A chat's upload store is its user's: nobody else sees it listed.
            if (!storeAccess.reachable(i, caller)) continue;
            String[] counts = counts(i);
            instances.row(Map.of(
                    "id", i.name(),
                    "name", i.name(),
                    "template", i.templateName(),
                    "scope", i.scope() + (i.scopeRef() == null ? "" : " (" + shortRef(i.scopeRef()) + ")"),
                    "index", stores.indexOf(scope.namespace(), i),
                    "entries", counts[0],
                    "chunks", counts[1]));
        }

        // The file store (Files API): raw uploads addressed by id, independent
        // of any store — attach them to a chat via POST /api/sessions/{id}/files.
        UiTable files = UiTable.of("vs-files-all", null).stackOnMobile(true)
                .column(UiTable.Column.text("fid", "Id"))
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("size", "Size"))
                .column(UiTable.Column.text("created", "Uploaded"))
                .column(UiTable.Column.text("used", "Used in"))
                .rowAction(UiAction.secondary("usage", "Used in…").icon("link")
                        .onClick(UiTrigger.api("GET", BASE + "/files/{id}/usage")))
                .rowAction(UiAction.secondary("download", "Download").icon("download")
                        .onClick(ai.mindconnect.ui.model.UiTrigger.openInTab(BASE + "/files/{id}/content")))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this file from the file store? The chats and stores that list it keep "
                                + "their entry until it is removed there.")
                        .dispatch("DELETE", "/admin/vector-stores/files/{id}"));
        // What the Files API gives the caller by id: their own files and those
        // stored before creators were recorded — nobody else's names and ids.
        FileUsages usages = fileUsages(caller);
        for (ai.mindconnect.filestore.StoredFile f : fileStore.list()) {
            if (!f.readableBy(caller)) continue;
            files.row(Map.of(
                    "id", f.id().value(),
                    "fid", f.id().value(),
                    "name", f.name(),
                    "type", f.contentType() == null ? "" : f.contentType(),
                    "size", readableSize(f.size()),
                    "created", f.createdAt() == null ? "" : f.createdAt().toString()
                            .replace("T", " ").substring(0, 16),
                    "used", usages.summary(f.id().value())));
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
        UiList header = headerFor(tab);

        // One tab per concern, each carrying its icon. The tables carry no
        // titles: the tab label names them and the header above names the
        // screen. Switching a tab is a client-side panel swap, but the
        // header's action belongs to the tab you are on — so each click also
        // asks the server for the matching header (a REPLACE patch on
        // vs-header). Without this, the button rendered for the initial tab
        // simply stayed, and the Stores tab had no way to create a store.
        UiSection tabs = UiSection.of("vs-page", null);
        tabs.getSections().add(UiSectionEntry.of("templates", "Templates", templates).icon("database")
                .onClick(ai.mindconnect.ui.model.UiTrigger.api("GET", BASE + "/header?tab=templates")));
        tabs.getSections().add(UiSectionEntry.of("indexes", "Indexes", indexesTable()).icon("server")
                .onClick(ai.mindconnect.ui.model.UiTrigger.api("GET", BASE + "/header?tab=indexes")));
        tabs.getSections().add(UiSectionEntry.of("stores", "Stores", instances).icon("server")
                .onClick(ai.mindconnect.ui.model.UiTrigger.api("GET", BASE + "/header?tab=stores")));
        tabs.getSections().add(UiSectionEntry.of("files", "Files", filesTab).icon("file")
                .onClick(ai.mindconnect.ui.model.UiTrigger.api("GET", BASE + "/header?tab=files")));
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
    private UiList headerFor(String tab) {
        UiList header = UiList.of("vs-header", "Vector Stores").icon("database");
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

    /** The header that matches a tab — fired by the tab's onClick, REPLACEs vs-header. */
    @GetMapping("/header")
    public ai.mindconnect.ui.model.UiPatch headerPatch(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String tab) {
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.replace("vs-header", headerFor(tab)));
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** The uploads are stored as the signed-in user's files — the file store records who put them there. */
    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UiPage uploadFiles(@org.springframework.web.bind.annotation.RequestParam("vs-files-upload")
                              List<org.springframework.web.multipart.MultipartFile> uploads) {
        ai.mindconnect.agent.UserId creator = currentUsers.require();
        for (var upload : uploads) {
            try (var content = upload.getInputStream()) {
                fileStore.save(upload.getOriginalFilename(), upload.getContentType(), content, creator);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Upload failed: " + e.getMessage(), e);
            }
        }
        return list("files");
    }

    /**
     * The files tab's Download button. The Files API takes bearer tokens only,
     * so the browser downloads here, with its session — and gets what the Files
     * API would give the signed-in user: their own files and those stored
     * before creators were recorded; anything else is 404.
     */
    @GetMapping("/files/{id}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> downloadStoredFile(
            @PathVariable String id) throws java.io.IOException {
        ai.mindconnect.agent.UserId caller = currentUsers.require();
        var file = fileStore.find(ai.mindconnect.filestore.FileId.of(id))
                .filter(f -> f.readableBy(caller)).orElse(null);
        if (file == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + file.name() + "\"")
                .contentType(file.contentType() != null
                        ? MediaType.parseMediaType(file.contentType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.InputStreamResource(fileStore.content(file.id())));
    }

    // ── indexes ────────────────────────────────────────────────────────────

    /** Every index the namespace can use: where it lives, which templates name it. */
    private UiNode indexesTable() {
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
        try {
            List<VectorStoreTemplate> templates = stores.templates(scope.namespace());
            for (IndexDefinition index : stores.indexes(scope.namespace())) {
                String where;
                try {
                    where = stores.indexLocation(scope.namespace(), index.name());
                } catch (RuntimeException e) {
                    where = "cannot be opened: " + e.getMessage();
                }
                String usedBy = templates.stream().filter(t -> index.name().equals(t.effectiveIndex()))
                        .map(VectorStoreTemplate::name).collect(java.util.stream.Collectors.joining(", "));
                boolean builtIn = stores.isBuiltIn(scope.namespace(), index.name());
                table.row(Map.of(
                        "id", index.name(),
                        "name", index.name() + (builtIn ? " (built-in)" : ""),
                        "where", where,
                        "templates", usedBy.isEmpty() ? "—" : usedBy,
                        "description", index.description() == null ? "" : index.description()));
            }
        } catch (RuntimeException e) {
            return UiText.of("vs-indexes", "The indexes cannot be listed: " + e.getMessage()).withCssClass("task-card-body");
        }
        return table;
    }

    @GetMapping("/indexes/new")
    public UiPage newIndex() {
        return indexForm(null);
    }

    /** A built-in index opens as the host defines it; saving it moves it for this namespace. */
    @GetMapping("/indexes/{name}/edit")
    public UiPage editIndex(@PathVariable String name) {
        return indexForm(stores.indexes(scope.namespace()).stream().filter(i -> i.name().equals(name))
                .findFirst().orElse(null));
    }

    private UiPage indexForm(IndexDefinition index) {
        boolean isNew = index == null;
        boolean builtIn = !isNew && stores.isBuiltIn(scope.namespace(), index.name());
        UiForm form = UiForm.of("vs-index-form", isNew ? "New Index"
                        : builtIn ? "Built-in Index: " + index.name() : "Edit Index: " + index.name())
                .field(UiField.text("name", "Name", isNew ? null : index.name()).asEditable()
                        .hint(builtIn ? "Saving keeps this name: the namespace's stores of it move to what you set here."
                                : "Lower case letters, digits, '.', '_' and '-' — what templates name"))
                .field(UiField.select("kind", "Kind", isNew ? IndexDefinition.PGVECTOR : index.kind(), List.of(
                        UiField.Option.of(IndexDefinition.PGVECTOR, "Postgres (pgvector)"),
                        UiField.Option.of(IndexDefinition.FILE, "Files"))).asEditable()
                        // Switching the kind swaps the fields below: a table and a database for
                        // pgvector, a directory for files.
                        .onChange(UiTrigger.api("POST", BASE + "/indexes/kind-fields", "vs-index-form")))
                .content(pgvectorGroup(!isNew && !index.pgvector(), isNew ? null : index.table(),
                        isNew ? null : index.url(), isNew ? null : index.user(), !isNew && index.password() != null))
                .content(fileGroup(isNew || index.pgvector(), isNew ? null : index.directory()))
                .field(UiField.text("description", "Description", isNew ? null : index.description()).asEditable());
        form.action(UiAction.primary("save", "Save").icon("save").dispatch("POST", BASE + "/indexes", "vs-index-form"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", BASE))
                .link(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + (isNew ? "/indexes/new" : "/indexes/" + index.name() + "/edit"), form);
    }

    private static ai.mindconnect.ui.model.UiFieldGroup pgvectorGroup(boolean hide, String table, String url,
                                                                     String user, boolean passwordSet) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("vs-index-pgvector", null)
                .field(UiField.text("table", "Table", table).asEditable()
                        .hint("e.g. mc_embedding_acme — empty: mc_embedding"))
                .field(UiField.text("url", "JDBC URL", url).asEditable()
                        .hint("A database of its own, e.g. jdbc:postgresql://db-acme:5432/vectors — empty: "
                                + "the application's database"))
                .field(UiField.text("user", "DB User", user).asEditable())
                .field(UiField.password("password", "DB Password", null).asEditable()
                        .hint(passwordSet ? "Set — leave empty to keep it"
                                : "Stored encrypted; ${ENV_VAR} reads it from the environment"));
        return hide ? group.hidden() : group;
    }

    private static ai.mindconnect.ui.model.UiFieldGroup fileGroup(boolean hide, String directory) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("vs-index-file", null)
                .field(UiField.text("directory", "Directory", directory).asEditable()
                        .hint("The directory under the namespace's data — empty: embeddings-<name>"));
        return hide ? group.hidden() : group;
    }

    /** The kind select changed: show the fields of the new kind, keeping what was typed. */
    @PostMapping("/indexes/kind-fields")
    public ai.mindconnect.ui.model.UiPatch indexKindFields(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        boolean pgvector = !IndexDefinition.FILE.equals(body.str("kind"));
        String name = body.str("name") == null ? null : body.str("name").trim();
        boolean passwordSet = name != null && stores.indexes(scope.namespace()).stream()
                .anyMatch(i -> i.name().equals(name) && i.password() != null);
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.replace("vs-index-pgvector", pgvectorGroup(!pgvector,
                        body.str("table"), body.str("url"), body.str("user"), passwordSet)))
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.replace("vs-index-file",
                        fileGroup(pgvector, body.str("directory"))));
    }

    @PostMapping("/indexes")
    public Object saveIndex(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name") == null ? null : body.str("name").trim();
        IndexDefinition previous = name == null ? null : stores.indexes(scope.namespace()).stream()
                .filter(i -> i.name().equals(name)).findFirst().orElse(null);
        String password = body.str("password");
        IndexDefinition index;
        // The hidden group of the other kind is submitted too; only the chosen kind's fields count.
        boolean pgvector = !IndexDefinition.FILE.equals(body.str("kind"));
        try {
            index = new IndexDefinition(name, body.str("kind"),
                    pgvector ? body.str("table") : null, pgvector ? body.str("url") : null,
                    pgvector ? body.str("user") : null,
                    !pgvector ? null
                            : password == null || password.isBlank() ? (previous == null ? null : previous.password())
                            : password,
                    pgvector ? null : body.str("directory"), body.str("description"));
            stores.saveIndex(scope.namespace(), index);
            // Open it now: a wrong URL or a database without pgvector shows here, not at the next upload.
            stores.indexLocation(scope.namespace(), index.name());
        } catch (IllegalArgumentException e) {
            return ai.mindconnect.ui.model.UiPatch.of().toast(UiToast.error(e.getMessage()).title("Not saved"));
        } catch (RuntimeException e) {
            return list("indexes").toast(UiToast.error("Saved, but it cannot be opened: " + e.getMessage()));
        }
        return list("indexes").toast(UiToast.success("Index '" + index.name() + "' saved: "
                + stores.indexLocation(scope.namespace(), index.name())));
    }

    @DeleteMapping("/indexes/{name}")
    public UiPage deleteIndex(@PathVariable String name) {
        if (stores.isBuiltIn(scope.namespace(), name)) {
            return list("indexes").toast(UiToast.error("'" + name + "' is built in: it follows the server's settings."));
        }
        stores.deleteIndex(scope.namespace(), name);
        return list("indexes");
    }

    // ── where a file is used ───────────────────────────────────────────────

    /**
     * Where the caller's files are used: the caller's chats that have them
     * attached — images included, which are never indexed — and the stores the
     * caller can reach that list them. A chat's upload store counts as its chat.
     */
    private record FileUsages(Map<String, java.util.LinkedHashMap<String, ai.mindconnect.agent.runtime.domain.AgentSession>> chats,
                              Map<String, List<VectorStoreInstance>> stores) {

        String summary(String fileId) {
            int c = chats.getOrDefault(fileId, new java.util.LinkedHashMap<>()).size();
            int s = stores.getOrDefault(fileId, List.of()).size();
            if (c == 0 && s == 0) return "—";
            List<String> parts = new java.util.ArrayList<>();
            if (c > 0) parts.add(c + (c == 1 ? " chat" : " chats"));
            if (s > 0) parts.add(s + (s == 1 ? " store" : " stores"));
            return String.join(" · ", parts);
        }
    }

    private FileUsages fileUsages(ai.mindconnect.agent.UserId caller) {
        Map<String, java.util.LinkedHashMap<String, ai.mindconnect.agent.runtime.domain.AgentSession>> chats = new java.util.HashMap<>();
        Map<String, ai.mindconnect.agent.runtime.domain.AgentSession> ownChats = new java.util.HashMap<>();
        for (var session : sessions.findByUser(caller)) {
            ownChats.put(session.id().value(), session);
            for (var attached : session.attachedFiles()) {
                chats.computeIfAbsent(attached.id(), k -> new java.util.LinkedHashMap<>())
                        .put(session.id().value(), session);
            }
        }
        Map<String, List<VectorStoreInstance>> inStores = new java.util.HashMap<>();
        try {
            var registry = stores.registry(scope.namespace());
            for (VectorStoreInstance instance : registry.instances()) {
                if (!storeAccess.reachable(instance, caller)) continue;
                for (var ref : registry.members(instance.name())) {
                    if (!ref.type().equals(ai.mindconnect.vectorstore.embedding.EntityType.FILE)) continue;
                    var chat = instance.scope() == VectorStoreInstance.Scope.SESSION && instance.scopeRef() != null
                            ? ownChats.get(instance.scopeRef()) : null;
                    if (chat != null) {
                        chats.computeIfAbsent(ref.id(), k -> new java.util.LinkedHashMap<>())
                                .put(chat.id().value(), chat);
                    } else {
                        inStores.computeIfAbsent(ref.id(), k -> new java.util.ArrayList<>()).add(instance);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Vector stores unreadable: the chats still say where a file is used.
        }
        return new FileUsages(chats, inStores);
    }

    private static final String USAGE_DIALOG = "vs-file-usage-dialog";

    /**
     * A dialog over the files tab: the file's chats (each opens the chat) and
     * stores (each opens the store).
     */
    @GetMapping("/files/{id}/usage")
    public ai.mindconnect.ui.model.UiPatch fileUsage(@PathVariable String id) {
        ai.mindconnect.agent.UserId caller = currentUsers.require();
        var file = fileStore.find(ai.mindconnect.filestore.FileId.of(id))
                .filter(f -> f.readableBy(caller)).orElse(null);
        if (file == null) {
            return ai.mindconnect.ui.model.UiPatch.of().toast(UiToast.error("There is no file '" + id + "'."));
        }
        FileUsages usages = fileUsages(caller);

        UiStack body = UiStack.of("vs-file-usage").gap(16);
        body.child(UiText.of("vs-file-usage-meta", id + " · " + readableSize(file.size())
                + (file.contentType() == null ? "" : " · " + file.contentType())).withCssClass("wf-run-meta"));

        var chatsOfFile = usages.chats().getOrDefault(id, new java.util.LinkedHashMap<>()).values();
        UiTable chats = UiTable.of("vs-file-chats", "Chats (" + chatsOfFile.size() + ")").stackOnMobile(true)
                .column(UiTable.Column.text("title", "Chat"))
                .column(UiTable.Column.text("started", "Started"))
                .rowAction(UiAction.secondary("open", "Open").icon("show").onClick(UiTrigger.go("/chat/sessions/{id}")));
        for (var chat : chatsOfFile) {
            chats.row(Map.of(
                    "id", chat.id().value(),
                    "title", chat.title() == null || chat.title().isBlank()
                            ? "Chat " + chat.id().value().substring(0, Math.min(8, chat.id().value().length()))
                            : chat.title(),
                    "started", chat.startedAt() == null ? "" : chat.startedAt().toString().replace("T", " ").substring(0, 16)));
        }
        body.child(chatsOfFile.isEmpty() ? UiText.of("vs-file-no-chats", "Not attached to any of your chats.") : chats);

        List<VectorStoreInstance> storesOfFile = usages.stores().getOrDefault(id, List.of());
        UiTable storeTable = UiTable.of("vs-file-stores", "Vector stores (" + storesOfFile.size() + ")").stackOnMobile(true)
                .column(UiTable.Column.text("name", "Store"))
                .column(UiTable.Column.text("template", "Template"))
                .column(UiTable.Column.text("scope", "Scope"))
                .rowAction(UiAction.secondary("view", "View").icon("show").onClick(UiTrigger.go(BASE + "/stores/{id}")));
        for (VectorStoreInstance instance : storesOfFile) {
            storeTable.row(Map.of(
                    "id", instance.name(),
                    "name", instance.name(),
                    "template", instance.templateName() == null ? "" : instance.templateName(),
                    "scope", instance.scope().toString()));
        }
        body.child(storesOfFile.isEmpty() ? UiText.of("vs-file-no-stores", "Not listed in any vector store.") : storeTable);

        ai.mindconnect.ui.model.UiDialog dialog = ai.mindconnect.ui.model.UiDialog.of("Used in: " + file.name(), null, body);
        dialog.setId(USAGE_DIALOG);
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.remove(USAGE_DIALOG))
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.append("sui-dialogs", dialog));
    }

    /** Only the uploader deletes a file — the Files API's rule; a file without a creator stays. */
    @DeleteMapping("/files/{id}")
    public UiPage deleteStoredFile(@PathVariable String id) throws java.io.IOException {
        var fileId = ai.mindconnect.filestore.FileId.of(id);
        if (fileStore.find(fileId).filter(f -> f.createdBy(currentUsers.require())).isEmpty()) {
            return list("files").toast(UiToast.error("Only the user who uploaded a file can delete it."));
        }
        fileStore.delete(fileId);
        return list("files");
    }

    /** How many entries the store lists and how many chunks the index has for them — "?" when it cannot say. */
    private String[] counts(VectorStoreInstance instance) {
        try {
            var entities = stores.store(scope.namespace(), instance.name()).entities();
            return new String[]{String.valueOf(entities.size()),
                    String.valueOf(entities.stream().mapToLong(e -> e.chunks()).sum())};
        } catch (RuntimeException e) {
            return new String[]{"?", "?"};
        }
    }

    private static String shortRef(String ref) {
        return ref.length() > 12 ? ref.substring(0, 12) + "…" : ref;
    }

    // ── template form ──────────────────────────────────────────────────────

    @GetMapping("/templates/new")
    public UiPage newTemplate() {
        return templateForm(null, false);
    }

    /**
     * The built-in template is not a file but the {@code mindconnect.vector-store.*}
     * properties, so editing it opens its values as an unnamed copy: name the
     * copy and it becomes a template of its own.
     */
    @GetMapping("/templates/{name}/edit")
    public UiPage editTemplate(@PathVariable String name) {
        if (VectorStores.DEFAULT_TEMPLATE.equals(name)) {
            return templateForm(stores.template(scope.namespace(), name).orElse(null), true);
        }
        return templateForm(stores.registry(scope.namespace()).template(name).orElse(null), false);
    }

    private static final String BUILT_IN_NOTE = "'" + VectorStores.DEFAULT_TEMPLATE
            + "' is the built-in template: it follows the mindconnect.vector-store.* properties "
            + "(embedding config: mindconnect.vector-store.embedding-config) and cannot be changed here.";

    private UiPage templateForm(VectorStoreTemplate t, boolean builtIn) {
        boolean isNew = t == null || builtIn;
        List<UiField.Option> embeddingOptions = llmConfigs.findAll().stream()
                .map(c -> UiField.Option.of(c.name(), c.name())).toList();
        UiForm form = UiForm.of("vs-template-form", builtIn ? "Built-in Template: " + t.name()
                        : isNew ? "New Template" : "Edit Template: " + t.name())
                .field(UiField.text("name", "Name", isNew ? null : t.name()).asEditable()
                        .hint(builtIn ? BUILT_IN_NOTE + " Give this copy a name to save it as a template of your own."
                                : "Unique template name, e.g. knowledge or chat-uploads"))
                // The version this form was opened with (0 for a new template): a
                // hidden input, submitted with the rest — the save is refused if
                // the template was saved since.
                .field(UiField.hidden("version",
                        isNew || t.version() == null ? "0" : t.version().toString()))
                .field(UiField.select("embeddingConfig", "Embedding Config",
                        t == null ? "embeddings" : t.embeddingConfig(), embeddingOptions)
                        .asEditable()
                        .hint("LlmConfig naming the embedding model — fixed per store at creation; "
                                + "changing it later never affects existing stores. Stores on the same "
                                + "model share the vectors of the files they both list."))
                .field(UiField.select("index", "Index", t == null ? IndexDefinition.DEFAULT : t.effectiveIndex(),
                                stores.indexes(scope.namespace()).stream()
                                        .map(i -> UiField.Option.of(i.name(), i.name())).toList())
                        .asEditable()
                        .hint("Where the chunks of this template's stores are kept — see the Indexes tab"))
                .field(UiField.text("ingestionWorkflow", "Ingestion Workflow",
                        t == null ? "file-ingestion" : t.ingestionWorkflow()).asEditable()
                        .hint("Workflow started by 'Ingest file…' on stores of this template"))
                .field(UiField.text("description", "Description",
                        t == null || builtIn ? null : t.metadata().get("description")).asEditable());
        form.action(UiAction.primary("save", builtIn ? "Save as new template" : "Save").icon("save")
                        .dispatch("POST", "/admin/vector-stores/templates", "vs-template-form"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", "/admin/vector-stores"))
                .link(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + (t == null ? "/templates/new" : "/templates/" + t.name() + "/edit"), form);
    }

    /**
     * Saves the template form against the version it was opened with. A new
     * template's form carries 0, so it does not overwrite one that exists under the
     * same name; an edit that renamed the template finds nothing under the new name
     * and saves it as a new template, as before.
     */
    @PostMapping("/templates")
    public Object saveTemplate(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name");
        String refusal = saveRefusal(name);
        if (refusal != null) {
            return list("templates").toast(UiToast.error(refusal));
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "description", body.str("description"));
        String templateName = name.trim();
        VectorStoreTemplate template = new VectorStoreTemplate(templateName,
                body.str("embeddingConfig") == null ? "embeddings" : body.str("embeddingConfig"),
                body.str("ingestionWorkflow"), metadata)
                .withIndex(body.str("index"))
                .withVersion(VersionedForms.version(body));
        try {
            stores.registry(scope.namespace()).saveTemplate(template);
        } catch (StaleVersionException e) {
            if (e.expectedVersion() == 0) {
                return ai.mindconnect.ui.model.UiPatch.of().toast(UiToast.error("A template named '"
                        + templateName + "' exists already — nothing was saved. Edit that one, or choose "
                        + "another name.").title("Not saved"));
            }
            if (e.storedVersion() != 0) {
                return VersionedForms.changedMeanwhile("Template '" + templateName + "'");
            }
            // Nothing stored under this name: the edit renamed the template — a new one.
            stores.registry(scope.namespace()).saveTemplate(template.withVersion(0L));
        }
        return list("templates").toast(UiToast.success("Template '" + templateName + "' saved."));
    }

    /** Why a template of this name cannot be saved — {@code null} when it can. */
    static String saveRefusal(String name) {
        if (name == null || name.isBlank()) {
            return "The template needs a name — nothing was saved.";
        }
        if (VectorStores.DEFAULT_TEMPLATE.equals(name.trim())) {
            return BUILT_IN_NOTE + " Save the copy under another name — nothing was saved.";
        }
        return null;
    }

    @DeleteMapping("/templates/{name}")
    public UiPage deleteTemplate(@PathVariable String name) {
        if (VectorStores.DEFAULT_TEMPLATE.equals(name)) {
            return list("templates").toast(UiToast.error(BUILT_IN_NOTE));
        }
        stores.registry(scope.namespace()).deleteTemplate(name);
        return list("templates");
    }

    // ── store instances ────────────────────────────────────────────────────

    @GetMapping("/stores/new")
    public UiPage newStore() {
        List<UiField.Option> templateOptions = stores.templates(scope.namespace()).stream()
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
        if (ai.mindconnect.agentrest.auth.VectorStoreAccess.isChatStoreName(name)) {
            return list("stores").toast(UiToast.error(ai.mindconnect.agentrest.auth.VectorStoreAccess.RESERVED_NAME));
        }
        if (name != null && !name.isBlank()) {
            stores.open(scope.namespace(), name.trim(), body.str("template"), VectorStoreInstance.Scope.GLOBAL, null);
        }
        return list("stores");
    }

    @GetMapping("/stores/{name}")
    public UiPage storeDetail(@PathVariable String name,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) String tab,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) String entry) {
        UiPage refused = refuseForeignStore(name);
        return refused != null ? refused : storeDetail(name, tab, entry, null, null, null);
    }

    private UiPage storeDetail(String name, String lastQuery, UiNode searchResult) {
        return storeDetail(name, "search", null, lastQuery, searchResult, null);
    }

    private UiPage storeDetail(String name, String lastQuery, UiNode searchResult, String message) {
        return storeDetail(name, "entries", null, lastQuery, searchResult, message);
    }

    private static final int PAGE_SIZE = 20;

    /**
     * The store: its settings, an upload when it has an ingestion workflow, and
     * three tabs — the entries it lists, the chunks they were cut into, and a
     * test search. Entries and chunks search and page on the server; the
     * search field and the page buttons fetch just their table again.
     */
    private UiPage storeDetail(String name, String tab, String entry, String lastQuery, UiNode searchResult,
                               String message) {
        VectorStore store = stores.store(scope.namespace(), name);
        VectorStoreInstance instance = store.settings();

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
        String indexName = stores.indexOf(scope.namespace(), instance);
        String location;
        try {
            location = stores.indexLocation(scope.namespace(), indexName);
        } catch (RuntimeException e) {
            location = "unknown (" + e.getMessage() + ")";
        }
        page.child(UiText.of("vs-detail-index", "index " + indexName + " · " + location).withCssClass("wf-run-meta"));
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
        tabs.getSections().add(UiSectionEntry.of("entries", "Entries", entriesTable(name, null, 1)).icon("file"));
        tabs.getSections().add(UiSectionEntry.of("chunks", "Chunks", chunksTable(name, null, entry, 1)).icon("list"));
        tabs.getSections().add(UiSectionEntry.of("search", "Search", searchTab).icon("search"));
        tabs.initialSection(tab == null || tab.isBlank() ? "entries" : tab);
        page.child(tabs);
        return UiPage.of(BASE + "/stores/" + name, page);
    }

    // ── entries tab ────────────────────────────────────────────────────────

    /** What the store lists, filtered by {@code q} (name, id or type), one page of it. */
    private UiNode entriesTable(String name, String q, int page) {
        String enc = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        UiForm searchForm = UiForm.of("vs-entries-search", null);
        searchForm.field(UiField.text("q", "", q).asEditable().icon("search")
                .placeholder("Search name, id or type…")
                .onChange(UiTrigger.api("POST", BASE + "/stores/" + enc + "/entries/search", "vs-entries-search")));
        UiTable table = UiTable.of("vs-entries", null).stackOnMobile(true)
                .headerExtra(searchForm)
                .column(UiTable.Column.text("file", "Entry"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.secondary("chunks", "Chunks").icon("list")
                        .onClick(UiTrigger.go(BASE + "/stores/" + enc + "?tab=chunks&entry={id}")))
                .rowAction(UiAction.danger("delete-file", "Remove").icon("delete")
                        .confirm("Take this entry off the store? A document goes with it; a stored file "
                                + "stays searchable in the other stores and chats that list it.")
                        // Ids may contain slashes (paths of documents from before 0.9) — they
                        // travel URL-encoded in the query, never in the path.
                        .dispatch("DELETE", BASE + "/stores/" + enc + "/files?file={id}"));
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        try {
            String needle = q == null || q.isBlank() ? null : q.toLowerCase(java.util.Locale.ROOT);
            for (var entity : stores.store(scope.namespace(), name).entities()) {
                String label = entryName(entity.ref());
                if (needle != null && !(label + " " + entity.ref().id() + " " + entity.ref().type().value())
                        .toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    continue;
                }
                rows.add(Map.of(
                        "id", java.net.URLEncoder.encode(entity.ref().id(), java.nio.charset.StandardCharsets.UTF_8),
                        "file", label,
                        "type", entity.ref().type().value(),
                        "chunks", String.valueOf(entity.chunks())));
            }
        } catch (RuntimeException e) {
            return UiText.of("vs-entries", "The entries cannot be listed: " + e.getMessage())
                    .withCssClass("task-card-body");
        }
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        int from = (current - 1) * PAGE_SIZE;
        rows.subList(from, Math.min(from + PAGE_SIZE, rows.size())).forEach(table::row);
        table.paginate(current, PAGE_SIZE, rows.size(), UiTrigger.api("GET",
                BASE + "/stores/" + enc + "/entries/table?page={page}" + param("q", q)));
        return table;
    }

    @GetMapping("/stores/{name}/entries/table")
    public ai.mindconnect.ui.model.UiPatch entriesPage(@PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page) {
        if (refuseForeignStore(name) != null) {
            return ai.mindconnect.ui.model.UiPatch.of().toast(UiToast.error("There is no store '" + name + "'."));
        }
        return ai.mindconnect.ui.model.UiPatch.of().patch(ai.mindconnect.ui.model.UiPatch.Operation.replace(
                "vs-entries", entriesTable(name, q, page == null ? 1 : page)));
    }

    /** The search field: a new search starts on page 1. */
    @PostMapping("/stores/{name}/entries/search")
    public ai.mindconnect.ui.model.UiPatch searchEntries(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        return entriesPage(name, new FormBody(raw).str("q"), 1);
    }

    // ── chunks tab ─────────────────────────────────────────────────────────

    /**
     * The chunks of the store's entries — of one entry when {@code entry} names
     * it — whose text contains {@code q}, one page of them, as the index has them.
     */
    private UiNode chunksTable(String name, String q, String entry, int page) {
        String enc = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        VectorStore store = stores.store(scope.namespace(), name);
        List<ai.mindconnect.vectorstore.embedding.EntityRef> members;
        try {
            members = store.members();
        } catch (RuntimeException e) {
            return UiText.of("vs-chunks", "The chunks cannot be listed: " + e.getMessage()).withCssClass("task-card-body");
        }
        List<UiField.Option> entries = new java.util.ArrayList<>();
        entries.add(UiField.Option.of("", "All entries"));
        Map<String, String> labels = new java.util.HashMap<>();
        for (var ref : members) {
            String label = entryName(ref);
            labels.put(ref.id(), label);
            entries.add(UiField.Option.of(ref.id(), label));
        }
        String selected = entry == null || entry.isBlank() ? null : entry;

        UiForm searchForm = UiForm.of("vs-chunks-search", null);
        var searchTrigger = UiTrigger.api("POST", BASE + "/stores/" + enc + "/chunks/search", "vs-chunks-search");
        searchForm.field(UiField.select("entry", "", selected == null ? "" : selected, entries).asEditable()
                .onChange(searchTrigger));
        searchForm.field(UiField.text("q", "", q).asEditable().icon("search")
                .placeholder("Search the chunks' text…").onChange(searchTrigger));
        UiTable table = UiTable.of("vs-chunks", null).stackOnMobile(true)
                .headerExtra(searchForm)
                .column(UiTable.Column.text("entry", "Entry"))
                .column(UiTable.Column.number("ordinal", "#"))
                .column(UiTable.Column.text("meta", "Metadata"))
                .column(UiTable.Column.text("text", "Text"));
        EmbeddingIndexPage result;
        try {
            var within = selected == null ? null : store.select(List.of(ai.mindconnect.vectorstore.tools.EntitySelector.id(selected)));
            int current = Math.max(page, 1);
            var chunkPage = store.chunks(within, q, (current - 1) * PAGE_SIZE, PAGE_SIZE);
            long pages = Math.max(1, (chunkPage.total() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (current > pages) {
                current = (int) pages;
                chunkPage = store.chunks(within, q, (current - 1) * PAGE_SIZE, PAGE_SIZE);
            }
            result = new EmbeddingIndexPage(chunkPage, current);
        } catch (RuntimeException e) {
            return UiText.of("vs-chunks", "The chunks cannot be listed: " + e.getMessage()).withCssClass("task-card-body");
        }
        for (var chunk : result.chunks().chunks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", chunk.ref().type().value() + ":" + chunk.ref().id() + "#" + chunk.chunk().id());
            row.put("entry", labels.getOrDefault(chunk.ref().id(), chunk.ref().id()));
            row.put("ordinal", String.valueOf(chunk.chunk().ordinal()));
            row.put("meta", chunk.chunk().metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining(" · ")));
            row.put("text", chunk.chunk().text());
            table.row(row);
        }
        table.paginate(result.page(), PAGE_SIZE, result.chunks().total(), UiTrigger.api("GET",
                BASE + "/stores/" + enc + "/chunks/table?page={page}" + param("q", q) + param("entry", selected)));
        return table;
    }

    private record EmbeddingIndexPage(ai.mindconnect.vectorstore.embedding.EmbeddingIndex.ChunkPage chunks, int page) {}

    @GetMapping("/stores/{name}/chunks/table")
    public ai.mindconnect.ui.model.UiPatch chunksPage(@PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String entry,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page) {
        if (refuseForeignStore(name) != null) {
            return ai.mindconnect.ui.model.UiPatch.of().toast(UiToast.error("There is no store '" + name + "'."));
        }
        return ai.mindconnect.ui.model.UiPatch.of().patch(ai.mindconnect.ui.model.UiPatch.Operation.replace(
                "vs-chunks", chunksTable(name, q, entry, page == null ? 1 : page)));
    }

    /** The search field and the entry choice: a new search starts on page 1. */
    @PostMapping("/stores/{name}/chunks/search")
    public ai.mindconnect.ui.model.UiPatch searchChunks(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        return chunksPage(name, body.str("q"), body.str("entry"), 1);
    }

    /** {@code &key=value}, URL-encoded; nothing for an empty value. */
    private static String param(String key, String value) {
        return value == null || value.isBlank() ? ""
                : "&" + key + "=" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A stored file by its name in the file store; anything else by its id. */
    private String entryName(ai.mindconnect.vectorstore.embedding.EntityRef ref) {
        if (ref.type().equals(ai.mindconnect.vectorstore.embedding.EntityType.FILE)) {
            try {
                return fileStore.find(ai.mindconnect.filestore.FileId.of(ref.id()))
                        .map(f -> f.name()).orElse(ref.id() + " (file removed)");
            } catch (RuntimeException e) {
                return ref.id();
            }
        }
        return ref.id();
    }

    @PostMapping("/stores/{name}/upload")
    public UiPage upload(@PathVariable String name,
                         @org.springframework.web.bind.annotation.RequestParam("vs-upload")
                         List<org.springframework.web.multipart.MultipartFile> files) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        StringBuilder message = new StringBuilder();
        for (var file : files) {
            String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
            try (var content = file.getInputStream()) {
                message.append(vectorStoreService.ingestUpload(name, fileName, file.getContentType(), content,
                        currentUsers.require())).append('\n');
            } catch (Exception e) {
                message.append(fileName).append(": failed — ").append(e.getMessage()).append('\n');
            }
        }
        return storeDetail(name, null, (UiNode) null, message.toString().stripTrailing());
    }

    @PostMapping("/stores/{name}/search")
    public UiPage search(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
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

        UiTable table = UiTable.of("vs-search-result", "Results (" + hits.size() + ")").stackOnMobile(true)
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
            row.put("id", hit.entityId() + "#" + hit.chunkId());
            row.put("rank", String.valueOf(rank++));
            row.put("score", String.format("%.3f", hit.score()));
            row.put("chunk", hit.metadata().getOrDefault("file", hit.entityId()) + " #" + hit.ordinal());
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
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        vectorStoreService.removeEntry(name, fileId);
        return storeDetail(name, null, (UiNode) null);
    }

    @DeleteMapping("/stores/{name}")
    public UiPage deleteStore(@PathVariable String name) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        vectorStoreService.deleteStore(name);
        return list("stores");
    }

    /**
     * The store overview with a "no such store" note when {@code name} is
     * another user's chat upload store; null when the caller may reach it.
     */
    private UiPage refuseForeignStore(String name) {
        if (storeAccess.reachable(name, stores.registry(scope.namespace()).instance(name).orElse(null), currentUsers.require())) {
            return null;
        }
        return list("stores").toast(UiToast.error("There is no store '" + name + "'."));
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
