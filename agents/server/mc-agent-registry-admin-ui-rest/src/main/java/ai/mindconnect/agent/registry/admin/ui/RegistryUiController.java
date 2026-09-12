package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry screen: the registries this installation knows, what each of
 * them offers, and the import.
 *
 * <p>Pages live under {@code /registry}, the actions behind them under
 * {@code /registry/api} — the split the MCP gateway and the workflow admin
 * use, so a host's layout advice can wrap the pages and leave the rest alone.
 */
@RestController
@RequestMapping(RegistryListView.BASE)
public class RegistryUiController {

    private static final Logger log = LoggerFactory.getLogger(RegistryUiController.class);

    private final RegistryService registry;

    public RegistryUiController(RegistryService registry) {
        this.registry = registry;
    }

    // ----------------------------------------------------------- the registries

    @GetMapping
    public UiPage page() {
        return list();
    }

    @GetMapping("/api")
    public UiPage list() {
        return UiPage.of(RegistryListView.BASE, new RegistryListView(registry.sources()).render());
    }

    @GetMapping({"/api/new", "/new"})
    public UiPage newSource() {
        return UiPage.of(RegistryListView.BASE + "/new",
                new RegistrySourceFormView(RegistrySourceDraft.empty(), true, null).render());
    }

    @GetMapping("/api/{id}/edit")
    public UiPage edit(@PathVariable String id) {
        return source(id)
                .map(source -> UiPage.of(RegistryListView.BASE + "/" + id + "/edit",
                        new RegistrySourceFormView(RegistrySourceDraft.of(source), false, null).render()))
                .orElseGet(this::list);
    }

    @PostMapping("/api/save")
    public UiPage save(@RequestBody Map<String, Object> body) {
        RegistrySourceDraft draft = RegistrySourceDraft.fromForm(body);
        RegistrySource existing = draft.id() == null ? null
                : source(draft.id()).orElse(null);
        try {
            registry.saveSource(draft.toSource(existing));
        } catch (RuntimeException e) {
            log.warn("Saving the registry '{}' failed: {}", draft.repository(), e.getMessage());
            return UiPage.of(RegistryListView.BASE + (existing == null ? "/new" : "/" + draft.id() + "/edit"),
                    new RegistrySourceFormView(draft, existing == null, message(e)).render());
        }
        return list();
    }

    @DeleteMapping("/api/{id}")
    public UiPage delete(@PathVariable String id) {
        sourceId(id).ifPresent(registry::deleteSource);
        return list();
    }

    // --------------------------------------------------------------- browsing

    @GetMapping("/{id}")
    public UiPage browsePage(@PathVariable String id) {
        return browse(id, null, null);
    }

    @GetMapping("/api/{id}")
    public UiPage browse(@PathVariable String id) {
        return browse(id, null, null);
    }

    @PostMapping("/api/{id}/search")
    public UiPage search(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object query = body.get("q");
        return browse(id, query == null ? null : query.toString(), typeOf(body.get("type")));
    }

    @PostMapping("/api/{id}/refresh")
    public UiPage refresh(@PathVariable String id) {
        sourceId(id).ifPresent(registry::refresh);
        return browse(id, null, null);
    }

    private UiPage browse(String id, String query, RegistryItemType type) {
        Optional<RegistrySource> found = source(id);
        if (found.isEmpty()) {
            return list();
        }
        RegistrySource source = found.get();
        RegistryIndex index;
        try {
            index = registry.index(source.id());
        } catch (RuntimeException e) {
            log.warn("Reading the registry {} failed: {}", source.coordinates(), e.getMessage());
            return UiPage.of(RegistryListView.BASE + "/" + id,
                    new RegistryUnreadableView(source, message(e)).render());
        }
        List<RegistryEntry> entries = index.search(query, type);
        return UiPage.of(RegistryListView.BASE + "/" + id,
                new RegistryBrowseView(source, index, entries, query, type, registry::status).render());
    }

    // --------------------------------------------------------------- one entry

    @GetMapping("/api/{id}/entry/{entryId}")
    public UiPage entry(@PathVariable String id, @PathVariable String entryId) {
        // Nobody has chosen yet: the view unticks what something else still uses.
        return entryPage(id, entryId, null, null, null);
    }

    /**
     * Imports the entry and shows what happened. A page rather than a patch:
     * an import changes the agents, workflows and configs of this installation,
     * and the report is the record of it — it should survive a click, not
     * vanish with the next one.
     *
     * <p>For a package the body is its Contents tab: an unticked Import box
     * leaves that entry out.
     */
    @PostMapping("/api/{id}/import/{entryId}")
    public UiPage importEntry(@PathVariable String id, @PathVariable String entryId,
                              @RequestParam(defaultValue = "SKIP_EXISTING") String mode,
                              @RequestBody(required = false) Map<String, Object> body) {
        Optional<RegistrySource> found = source(id);
        if (found.isEmpty()) {
            return list();
        }
        Set<String> excluded = RegistryEntryView.excludedFrom(body);
        try {
            ImportReport report = registry.importEntry(found.get().id(), entryId, modeOf(mode), excluded);
            return entryPage(id, entryId, excluded, report, null);
        } catch (RuntimeException e) {
            log.warn("Importing '{}' from registry '{}' failed: {}", entryId, id, e.getMessage());
            return entryPage(id, entryId, excluded, null, message(e));
        }
    }

    /**
     * Removes a package: deletes the entries included on its Contents tab that
     * are here, and shows what happened on the same page.
     */
    @PostMapping("/api/{id}/remove/{entryId}")
    public UiPage removeEntry(@PathVariable String id, @PathVariable String entryId,
                              @RequestBody(required = false) Map<String, Object> body) {
        Optional<RegistrySource> found = source(id);
        if (found.isEmpty()) {
            return list();
        }
        Set<String> kept = RegistryEntryView.excludedFrom(body);
        try {
            ImportReport report = registry.removeEntry(found.get().id(), entryId, kept);
            return entryPage(id, entryId, kept, report, null);
        } catch (RuntimeException e) {
            log.warn("Removing '{}' of registry '{}' failed: {}", entryId, id, e.getMessage());
            return entryPage(id, entryId, kept, null, message(e));
        }
    }

    private UiPage entryPage(String id, String entryId, Set<String> excluded,
                             ImportReport report, String error) {
        Optional<RegistrySource> found = source(id);
        if (found.isEmpty()) {
            return list();
        }
        RegistrySource source = found.get();
        RegistryIndex index;
        try {
            index = registry.index(source.id());
        } catch (RuntimeException e) {
            return UiPage.of(RegistryListView.BASE + "/" + id,
                    new RegistryUnreadableView(source, message(e)).render());
        }
        Optional<RegistryEntry> entry = index.find(entryId);
        if (entry.isEmpty()) {
            return browse(id, null, null);
        }
        RegistryService.PackageContents contents = null;
        String contentsError = null;
        if (entry.get().type() == RegistryItemType.PACKAGE) {
            try {
                contents = registry.packageContents(source.id(), entry.get());
            } catch (RuntimeException e) {
                // The details and the import still work; only the list tab says why it is empty.
                log.warn("Reading package '{}' from {} failed: {}", entryId, source.coordinates(), e.getMessage());
                contentsError = message(e);
            }
        }
        return UiPage.of(RegistryListView.BASE + "/" + id + "/entry/" + entryId,
                new RegistryEntryView(source, index, entry.get(), registry::status,
                        contents, contentsError, excluded, report, error).render());
    }

    // ----------------------------------------------------------------- helpers

    private Optional<RegistrySource> source(String id) {
        return sourceId(id).flatMap(registry::findSource);
    }

    /**
     * The id from a URL, or empty when the text could not address a registry at
     * all. Empty rather than thrown: an address nobody could have configured is
     * simply not found, and a form being re-rendered must not fail again on the
     * same text.
     */
    private Optional<RegistrySourceId> sourceId(String value) {
        try {
            return Optional.of(RegistrySourceId.of(value));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private static RegistryItemType typeOf(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return RegistryItemType.fromWire(value.toString());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static ImportMode modeOf(String value) {
        try {
            return ImportMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return ImportMode.SKIP_EXISTING;
        }
    }

    /** What to put on screen for a failure — the exception's words, or its name. */
    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
