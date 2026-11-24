package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.VectorStoreChunksComponent;
import ai.mindconnect.adminui.ui.component.VectorStoreEntriesComponent;
import ai.mindconnect.adminui.ui.component.VectorStoreHitsComponent;
import ai.mindconnect.adminui.ui.page.VectorStoreDetailPage;
import ai.mindconnect.adminui.ui.page.VectorStoreFormPage;
import ai.mindconnect.adminui.ui.page.VectorStoreOverviewPage;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.agentrest.auth.VectorStoreAccess;
import ai.mindconnect.agentrest.service.VectorStoreContents;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Admin UI for vector stores: the overview and the store instances created
 * from templates. Templates, indexes and stored files have controllers of
 * their own under the same path. Everything goes through the
 * {@link VectorStoreService} the REST API uses, so what the screen shows is
 * what the API and the tools do.
 *
 * <p>Templates and knowledge bases are shared configuration. A chat's upload
 * store is its user's alone ({@link VectorStoreAccess}): another user neither
 * sees it listed nor opens, searches, fills or deletes it.
 */
@RestController
@RequestMapping("/admin/vector-stores")
public class VectorStoreUiController {

    private static final int PAGE_SIZE = 20;

    private final VectorStoreService vectorStoreService;
    private final VectorStoreContents contents;
    private final CurrentUsers currentUsers;
    private final VectorStoreAccess storeAccess;
    private final VectorStoreOverview overview;

    VectorStoreUiController(VectorStoreService vectorStoreService, VectorStoreContents contents,
                            CurrentUsers currentUsers, VectorStoreAccess storeAccess,
                            VectorStoreOverview overview) {
        this.vectorStoreService = vectorStoreService;
        this.contents = contents;
        this.currentUsers = currentUsers;
        this.storeAccess = storeAccess;
        this.overview = overview;
    }

    @GetMapping
    public UiPage list() {
        return overview.page("templates");
    }

    /** The header that matches a tab — fired by the tab's onClick, REPLACEs vs-header. */
    @GetMapping("/header")
    public UiPatch headerPatch(@RequestParam(required = false) String tab) {
        return UiPatch.of().patch(UiPatch.Operation.replace(VectorStoreOverviewPage.HEADER_ID,
                VectorStoreOverviewPage.header(tab)));
    }

    @GetMapping("/stores/new")
    public UiPage newStore() {
        return new VectorStoreFormPage(vectorStoreService.templates().stream()
                .map(VectorStoreTemplate::name).toList()).render();
    }

    @PostMapping("/stores")
    public UiPage createStore(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name");
        if (VectorStoreAccess.isChatStoreName(name)) {
            return overview.page("stores").toast(UiToast.error(VectorStoreAccess.RESERVED_NAME));
        }
        if (name != null && !name.isBlank()) {
            vectorStoreService.createStore(name, body.str("template"));
        }
        return overview.page("stores");
    }

    @GetMapping("/stores/{name}")
    public UiPage storeDetail(@PathVariable String name,
                              @RequestParam(required = false) String tab,
                              @RequestParam(required = false) String entry) {
        UiPage refused = refuseForeignStore(name);
        return refused != null ? refused : detail(name, entry).opening(tab).render();
    }

    /** The store's page with both tables on their first page; {@code entry} limits the chunks to one entry. */
    private VectorStoreDetailPage detail(String name, String entry) {
        VectorStoreInstance instance = vectorStoreService.settings(name);
        String indexName = vectorStoreService.indexOf(instance);
        String location;
        try {
            location = vectorStoreService.indexLocation(indexName);
        } catch (RuntimeException e) {
            location = "unknown (" + e.getMessage() + ")";
        }
        return new VectorStoreDetailPage(name, instance, indexName, location,
                entriesTable(name, null, 1), chunksTable(name, null, entry, 1));
    }

    private UiNode entriesTable(String name, String q, int page) {
        try {
            return new VectorStoreEntriesComponent(name, q, contents.entries(name, q, page, PAGE_SIZE)).render();
        } catch (RuntimeException e) {
            return VectorStoreEntriesComponent.unavailable(e.getMessage());
        }
    }

    private UiNode chunksTable(String name, String q, String entry, int page) {
        String selected = entry == null || entry.isBlank() ? null : entry;
        try {
            return new VectorStoreChunksComponent(name, q, selected, contents.entryNames(name),
                    contents.chunks(name, selected, q, page, PAGE_SIZE)).render();
        } catch (RuntimeException e) {
            return VectorStoreChunksComponent.unavailable(e.getMessage());
        }
    }

    @GetMapping("/stores/{name}/entries/table")
    public UiPatch entriesPage(@PathVariable String name,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) Integer page) {
        if (refuseForeignStore(name) != null) {
            return UiPatch.of().toast(UiToast.error("There is no store '" + name + "'."));
        }
        return UiPatch.of().patch(UiPatch.Operation.replace(
                VectorStoreEntriesComponent.ID, entriesTable(name, q, page == null ? 1 : page)));
    }

    /** The search field: a new search starts on page 1. */
    @PostMapping("/stores/{name}/entries/search")
    public UiPatch searchEntries(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        return entriesPage(name, new FormBody(raw).str("q"), 1);
    }

    @GetMapping("/stores/{name}/chunks/table")
    public UiPatch chunksPage(@PathVariable String name,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String entry,
                              @RequestParam(required = false) Integer page) {
        if (refuseForeignStore(name) != null) {
            return UiPatch.of().toast(UiToast.error("There is no store '" + name + "'."));
        }
        return UiPatch.of().patch(UiPatch.Operation.replace(
                VectorStoreChunksComponent.ID, chunksTable(name, q, entry, page == null ? 1 : page)));
    }

    /** The search field and the entry choice: a new search starts on page 1. */
    @PostMapping("/stores/{name}/chunks/search")
    public UiPatch searchChunks(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        return chunksPage(name, body.str("q"), body.str("entry"), 1);
    }

    @PostMapping("/stores/{name}/upload")
    public UiPage upload(@PathVariable String name, @RequestParam("vs-upload") List<MultipartFile> files) {
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
        return detail(name, null).opening("entries").saying(message.toString().stripTrailing()).render();
    }

    @PostMapping("/stores/{name}/search")
    public UiPage search(@PathVariable String name, @RequestBody Map<String, Object> raw) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        var body = new FormBody(raw);
        String query = body.str("query");
        if (query == null || query.isBlank()) {
            return detail(name, null).opening("search").render();
        }
        int topK = Math.max(1, Math.min(body.num("topK", 5), 50));
        double minScore = Math.max(0, Math.min(body.num("minScore", 0), 100)) / 100.0;
        UiNode result;
        try {
            result = new VectorStoreHitsComponent(vectorStoreService.search(name, query, topK, minScore)).render();
        } catch (RuntimeException e) {
            result = VectorStoreHitsComponent.failed(e.getMessage());
        }
        return detail(name, null).opening("search").searched(query, result).render();
    }

    @DeleteMapping("/stores/{name}/files")
    public UiPage deleteFile(@PathVariable String name, @RequestParam("file") String fileId) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        vectorStoreService.removeEntry(name, fileId);
        return detail(name, null).opening("search").render();
    }

    @DeleteMapping("/stores/{name}")
    public UiPage deleteStore(@PathVariable String name) {
        UiPage refused = refuseForeignStore(name);
        if (refused != null) return refused;
        vectorStoreService.deleteStore(name);
        return overview.page("stores");
    }

    /**
     * The store overview with a "no such store" note when {@code name} is
     * another user's chat upload store; null when the caller may reach it.
     */
    private UiPage refuseForeignStore(String name) {
        if (storeAccess.reachable(name, vectorStoreService.instance(name).orElse(null), currentUsers.require())) {
            return null;
        }
        return overview.page("stores").toast(UiToast.error("There is no store '" + name + "'."));
    }
}
