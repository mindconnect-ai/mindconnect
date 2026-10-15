package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

/** The form for adding or editing a registry. */
final class RegistrySourceFormView {

    private static final String FORM_ID = "registry-source-form";
    static final String STACK_ID = FORM_ID + "-stack";

    private final RegistrySourceDraft draft;
    private final boolean isNew;
    private final String error;

    RegistrySourceFormView(RegistrySourceDraft draft, boolean isNew, String error) {
        this.draft = draft == null ? RegistrySourceDraft.empty() : draft;
        this.isNew = isNew;
        this.error = error;
    }

    UiNode render() {
        UiForm form = UiForm.of(FORM_ID, isNew ? "Add registry" : "Registry " + draft.name());

        form.field(UiField.text("repository", "Repository", draft.repository())
                .asEditable().asRequired()
                .icon("box")
                .placeholder("owner/repo")
                .hint("The GitHub project holding the registry — 'owner/repo', or the URL from "
                        + "the browser's address bar. Shorthands work too: "
                        + "'owner/repo@v1.2.0' pins a tag."));

        form.field(UiField.text("name", "Name", draft.name())
                .asEditable()
                .hint("What to call it in this list. Defaults to owner/repo."));

        form.field(UiField.text("ref", "Branch, tag or commit", draft.ref())
                .asEditable()
                .placeholder(RegistrySource.DEFAULT_REF)
                .hint("What is read. A branch is whatever its owner pushed last — for a registry "
                        + "you do not control, pin a tag and move it deliberately."));

        form.field(UiField.text("indexPath", "Index path", draft.indexPath())
                .asEditable()
                .placeholder(RegistrySource.DEFAULT_INDEX_PATH)
                .hint("Where the index sits inside the repository."));

        form.field(UiField.text("tokenEnvVar", "Token variable", draft.tokenEnvVar())
                .asEditable()
                .hint("For a private repository: the name of the environment variable holding the "
                        + "token — the name, not the token. This installation reads it at request "
                        + "time, so nothing secret is ever stored here."));

        form.field(UiField.text("baseUrl", "Raw content URL", draft.baseUrl())
                .asEditable()
                .placeholder(RegistrySource.GITHUB_RAW_BASE_URL)
                .hint("Only for GitHub Enterprise. Leave empty for github.com."));

        form.field(UiField.bool("enabled", "Enabled", draft.enabled())
                .asEditable()
                .hint("A disabled registry stays configured but is not read."));

        form.action(UiAction.primary("save", "Save").icon("save")
                        .dispatch("POST", RegistryListView.BASE + "/api/save", FORM_ID))
                .action(UiAction.secondary("back", "All registries").icon("back")
                        .dispatch("GET", RegistryListView.BASE + "/api"));

        UiStack stack = UiStack.of(STACK_ID);
        stack.child(form);
        if (error != null) {
            stack.child(UiStack.of(FORM_ID + "-error")
                    .<UiStack>withCssClass("llm-test-result")
                    .child(UiText.of(FORM_ID + "-error-text", "✗ " + error)
                            .<UiText>withCssClass("llm-test-body")));
        }
        return stack;
    }
}
