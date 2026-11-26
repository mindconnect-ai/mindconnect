package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.vectorstore.tools.VectorStores;

import java.util.List;

/** The form that creates a store from a template, at {@code /admin/vector-stores/stores/new}. */
public final class VectorStoreFormPage extends AdminPage {

    private static final String BASE = "/admin/vector-stores";

    private final List<String> templates;

    /** @param templates the names of the templates a store can be created from */
    public VectorStoreFormPage(List<String> templates) {
        this.templates = templates;
    }

    @Override
    public UiPage render() {
        List<UiField.Option> templateOptions = templates.stream()
                .map(name -> UiField.Option.of(name, name)).toList();
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
}
