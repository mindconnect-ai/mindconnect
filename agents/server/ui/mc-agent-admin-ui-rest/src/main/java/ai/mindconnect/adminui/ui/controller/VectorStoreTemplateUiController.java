package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.VectorStoreTemplateFormPage;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The templates tab of the vector stores screen: the policies — embedding
 * config, index, ingestion workflow — stores are created from.
 */
@RestController
@RequestMapping("/admin/vector-stores/templates")
public class VectorStoreTemplateUiController {

    private final VectorStoreService vectorStoreService;
    private final LlmConfigRepository llmConfigs;
    private final VectorStoreOverview overview;

    VectorStoreTemplateUiController(VectorStoreService vectorStoreService, LlmConfigRepository llmConfigs,
                                    VectorStoreOverview overview) {
        this.vectorStoreService = vectorStoreService;
        this.llmConfigs = llmConfigs;
        this.overview = overview;
    }

    @GetMapping("/new")
    public UiPage newTemplate() {
        return form(null, false);
    }

    /**
     * The built-in template is not a file but the {@code mindconnect.vector-store.*}
     * properties, so editing it opens its values as an unnamed copy: name the
     * copy and it becomes a template of its own.
     */
    @GetMapping("/{name}/edit")
    public UiPage editTemplate(@PathVariable String name) {
        return form(vectorStoreService.template(name).orElse(null), VectorStores.DEFAULT_TEMPLATE.equals(name));
    }

    private UiPage form(VectorStoreTemplate template, boolean builtIn) {
        return new VectorStoreTemplateFormPage(template, builtIn,
                llmConfigs.findAll().stream().map(config -> config.name()).toList(),
                vectorStoreService.indexes().stream().map(i -> i.definition().name()).toList()).render();
    }

    /**
     * Saves the template form against the version it was opened with. A new
     * template's form carries 0, so it does not overwrite one that exists under the
     * same name; an edit that renamed the template finds nothing under the new name
     * and saves it as a new template, as before.
     */
    @PostMapping
    public Object saveTemplate(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String name = body.str("name");
        String refusal = saveRefusal(name);
        if (refusal != null) {
            return overview.page("templates").toast(UiToast.error(refusal));
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        String description = body.str("description");
        if (description != null && !description.isBlank()) {
            metadata.put("description", description);
        }
        String templateName = name.trim();
        VectorStoreTemplate template = new VectorStoreTemplate(templateName,
                body.str("embeddingConfig") == null ? "embeddings" : body.str("embeddingConfig"),
                body.str("ingestionWorkflow"), metadata)
                .withIndex(body.str("index"))
                .withVersion(VersionedForms.version(body));
        try {
            vectorStoreService.saveTemplate(template);
        } catch (StaleVersionException e) {
            if (e.expectedVersion() == 0) {
                return UiPatch.of().toast(UiToast.error("A template named '"
                        + templateName + "' exists already — nothing was saved. Edit that one, or choose "
                        + "another name.").title("Not saved"));
            }
            if (e.storedVersion() != 0) {
                return VersionedForms.changedMeanwhile("Template '" + templateName + "'");
            }
            // Nothing stored under this name: the edit renamed the template — a new one.
            vectorStoreService.saveTemplate(template.withVersion(0L));
        }
        return overview.page("templates").toast(UiToast.success("Template '" + templateName + "' saved."));
    }

    /** Why a template of this name cannot be saved — {@code null} when it can. */
    static String saveRefusal(String name) {
        if (name == null || name.isBlank()) {
            return "The template needs a name — nothing was saved.";
        }
        if (VectorStores.DEFAULT_TEMPLATE.equals(name.trim())) {
            return VectorStoreTemplateFormPage.BUILT_IN_NOTE + " Save the copy under another name — nothing was saved.";
        }
        return null;
    }

    @DeleteMapping("/{name}")
    public UiPage deleteTemplate(@PathVariable String name) {
        if (VectorStores.DEFAULT_TEMPLATE.equals(name)) {
            return overview.page("templates").toast(UiToast.error(VectorStoreTemplateFormPage.BUILT_IN_NOTE));
        }
        vectorStoreService.deleteTemplate(name);
        return overview.page("templates");
    }
}
