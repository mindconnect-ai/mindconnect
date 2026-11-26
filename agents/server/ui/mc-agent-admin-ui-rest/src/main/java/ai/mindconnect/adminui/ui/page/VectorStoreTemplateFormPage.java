package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;

import java.util.List;

/**
 * The form of one template: a new one, or an existing one to edit. The
 * built-in template is not a file but the {@code mindconnect.vector-store.*}
 * properties, so it opens as an unnamed copy: name the copy and it becomes a
 * template of its own.
 */
public final class VectorStoreTemplateFormPage extends AdminPage {

    public static final String BUILT_IN_NOTE = "'" + VectorStores.DEFAULT_TEMPLATE
            + "' is the built-in template: it follows the mindconnect.vector-store.* properties "
            + "(embedding config: mindconnect.vector-store.embedding-config) and cannot be changed here.";

    private static final String BASE = "/admin/vector-stores";

    private final VectorStoreTemplate template;
    private final boolean builtIn;
    private final List<String> embeddingConfigs;
    private final List<String> indexes;

    /**
     * @param template         the template to edit; {@code null} for a new one
     * @param builtIn          whether {@code template} is the built-in one, opened as a copy
     * @param embeddingConfigs the names of the LLM configs an embedding config can be chosen from
     * @param indexes          the names of the indexes a template can keep its chunks in
     */
    public VectorStoreTemplateFormPage(VectorStoreTemplate template, boolean builtIn, List<String> embeddingConfigs,
                                       List<String> indexes) {
        this.template = template;
        this.builtIn = builtIn;
        this.embeddingConfigs = embeddingConfigs;
        this.indexes = indexes;
    }

    @Override
    public UiPage render() {
        VectorStoreTemplate t = template;
        boolean isNew = t == null || builtIn;
        List<UiField.Option> embeddingOptions = embeddingConfigs.stream()
                .map(name -> UiField.Option.of(name, name)).toList();
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
                                indexes.stream().map(index -> UiField.Option.of(index, index)).toList())
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
}
