package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.VectorIndexFormPage;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The indexes tab of the vector stores screen: where the chunks of a template's stores are kept. */
@RestController
@RequestMapping("/admin/vector-stores/indexes")
public class VectorIndexUiController {

    private final VectorStoreService vectorStoreService;
    private final VectorStoreOverview overview;

    VectorIndexUiController(VectorStoreService vectorStoreService, VectorStoreOverview overview) {
        this.vectorStoreService = vectorStoreService;
        this.overview = overview;
    }

    @GetMapping("/new")
    public UiPage newIndex() {
        return new VectorIndexFormPage(null, vectorStoreService.systemDatabase()).render();
    }

    /** A built-in index opens as the host defines it; saving it moves it for this namespace. */
    @GetMapping("/{name}/edit")
    public UiPage editIndex(@PathVariable String name) {
        return new VectorIndexFormPage(vectorStoreService.indexes().stream()
                .filter(i -> i.definition().name().equals(name)).findFirst().orElse(null),
                vectorStoreService.systemDatabase()).render();
    }

    /** The kind or the database select changed: show the fields that now apply, keeping what was typed. */
    @PostMapping("/kind-fields")
    public UiPatch indexKindFields(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name") == null ? null : body.str("name").trim();
        boolean passwordSet = name != null && vectorStoreService.indexes().stream()
                .anyMatch(i -> i.definition().name().equals(name) && i.definition().password() != null);
        return VectorIndexFormPage.kindFields(body, passwordSet, vectorStoreService.systemDatabase());
    }

    @PostMapping
    public Object saveIndex(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name") == null ? null : body.str("name").trim();
        String password = body.str("password");
        IndexDefinition index;
        // Hidden groups are submitted too; only the fields of the chosen kind and database count.
        boolean pgvector = !IndexDefinition.FILE.equals(body.str("kind"));
        boolean ownDatabase = pgvector && VectorIndexFormPage.OWN_DATABASE.equals(body.str("database"));
        try {
            index = new IndexDefinition(name, body.str("kind"),
                    pgvector ? body.str("table") : null, ownDatabase ? body.str("url") : null,
                    ownDatabase ? body.str("user") : null,
                    // Left empty, the service keeps the password stored under this name.
                    !ownDatabase || password == null || password.isBlank() ? null : password,
                    pgvector ? null : body.str("directory"), body.str("description"));
            if (ownDatabase && index.url() == null) {
                return UiPatch.of().toast(UiToast.error(
                        "A database of its own needs a JDBC URL — or choose the application's database.")
                        .title("Not saved"));
            }
            vectorStoreService.saveIndex(index);
            // Open it now: a wrong URL or a database without pgvector shows here, not at the next upload.
            vectorStoreService.indexLocation(index.name());
        } catch (IllegalArgumentException e) {
            return UiPatch.of().toast(UiToast.error(e.getMessage()).title("Not saved"));
        } catch (RuntimeException e) {
            return overview.page("indexes").toast(UiToast.error("Saved, but it cannot be opened: " + e.getMessage()));
        }
        return overview.page("indexes").toast(UiToast.success("Index '" + index.name() + "' saved: "
                + vectorStoreService.indexLocation(index.name())));
    }

    @DeleteMapping("/{name}")
    public UiPage deleteIndex(@PathVariable String name) {
        if (!vectorStoreService.deleteIndex(name)) {
            return overview.page("indexes")
                    .toast(UiToast.error("'" + name + "' is built in: it follows the server's settings."));
        }
        return overview.page("indexes");
    }
}
