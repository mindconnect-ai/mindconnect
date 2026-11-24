package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.agentrest.service.VectorStoreService.IndexInfo;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.vectorstore.tools.IndexDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The form of one index: a new one, or an existing one to edit. A built-in
 * index opens as the host defines it; saving it moves it for this namespace.
 */
public final class VectorIndexFormPage extends AdminPage {

    /** The index uses the database the application has — no URL, user or password to give. */
    public static final String SYSTEM_DATABASE = "system";
    /** The index names a database of its own by JDBC URL. */
    public static final String OWN_DATABASE = "own";

    private static final String BASE = "/admin/vector-stores";

    private final IndexInfo info;
    private final Optional<String> systemDatabase;

    /**
     * @param info           the index to edit; {@code null} for a new one
     * @param systemDatabase where the application's own database is; empty when it runs without one
     */
    public VectorIndexFormPage(IndexInfo info, Optional<String> systemDatabase) {
        this.info = info;
        this.systemDatabase = systemDatabase;
    }

    @Override
    public UiPage render() {
        boolean isNew = info == null;
        IndexDefinition index = isNew ? null : info.definition();
        boolean builtIn = !isNew && info.builtIn();
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
                        isNew || index.url() == null ? SYSTEM_DATABASE : OWN_DATABASE, systemDatabase))
                .content(ownDatabaseGroup((!isNew && !index.pgvector()) || isNew || index.url() == null,
                        isNew ? null : index.url(), isNew ? null : index.user(), !isNew && index.password() != null))
                .content(fileGroup(isNew || index.pgvector(), isNew ? null : index.directory()))
                .field(UiField.text("description", "Description", isNew ? null : index.description()).asEditable());
        form.action(UiAction.primary("save", "Save").icon("save").dispatch("POST", BASE + "/indexes", "vs-index-form"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", BASE))
                .link(UiLink.of("back", BASE, "← Back to Vector Stores"));
        return UiPage.of(BASE + (isNew ? "/indexes/new" : "/indexes/" + index.name() + "/edit"), form);
    }

    /**
     * The kind or the database select changed: show the fields that now apply, keeping what was typed.
     *
     * @param passwordSet whether a password is stored for the index the form names
     */
    public static UiPatch kindFields(FormBody body, boolean passwordSet, Optional<String> systemDatabase) {
        boolean pgvector = !IndexDefinition.FILE.equals(body.str("kind"));
        String database = OWN_DATABASE.equals(body.str("database")) ? OWN_DATABASE : SYSTEM_DATABASE;
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("vs-index-pgvector", pgvectorGroup(!pgvector,
                        body.str("table"), database, systemDatabase)))
                .patch(UiPatch.Operation.replace("vs-index-own-db",
                        ownDatabaseGroup(!pgvector || SYSTEM_DATABASE.equals(database), body.str("url"),
                                body.str("user"), passwordSet)))
                .patch(UiPatch.Operation.replace("vs-index-file",
                        fileGroup(pgvector, body.str("directory"))));
    }

    private static UiFieldGroup pgvectorGroup(boolean hide, String table, String database,
                                              Optional<String> systemDatabase) {
        var group = UiFieldGroup.of("vs-index-pgvector", null)
                .field(UiField.text("table", "Table", table).asEditable()
                        .hint("e.g. mc_embedding_acme — empty: mc_embedding"))
                .field(UiField.select("database", "Database", database, List.of(
                                UiField.Option.of(SYSTEM_DATABASE, "The application's database"),
                                UiField.Option.of(OWN_DATABASE, "A database of its own (JDBC URL)")))
                        .asEditable()
                        .hint(systemDatabase.map(where -> "The application's database is " + where + ".")
                                .orElse("The application runs without Postgres: an index on its database falls "
                                        + "back to files — give it a database of its own."))
                        // Only a database of its own asks for URL, user and password.
                        .onChange(UiTrigger.api("POST", BASE + "/indexes/kind-fields", "vs-index-form")));
        return hide ? group.hidden() : group;
    }

    private static UiFieldGroup ownDatabaseGroup(boolean hide, String url, String user, boolean passwordSet) {
        var group = UiFieldGroup.of("vs-index-own-db", null)
                .field(UiField.text("url", "JDBC URL", url).asEditable()
                        .hint("e.g. jdbc:postgresql://db-acme:5432/vectors"))
                .field(UiField.text("user", "DB User", user).asEditable())
                .field(UiField.password("password", "DB Password", null).asEditable()
                        .hint(passwordSet ? "Set — leave empty to keep it"
                                : "Stored encrypted; ${ENV_VAR} reads it from the environment"));
        return hide ? group.hidden() : group;
    }

    private static UiFieldGroup fileGroup(boolean hide, String directory) {
        var group = UiFieldGroup.of("vs-index-file", null)
                .field(UiField.text("directory", "Directory", directory).asEditable()
                        .hint("The directory under the namespace's data — empty: embeddings-<name>"));
        return hide ? group.hidden() : group;
    }
}
