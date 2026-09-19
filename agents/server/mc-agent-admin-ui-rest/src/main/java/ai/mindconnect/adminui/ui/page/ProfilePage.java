package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolVariable;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.User;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own page at {@code /admin/profile}, reached from the
 * avatar in the header: who they are signed in as, and the personal API
 * tokens they issued — the way a program calls the REST API as them — and
 * the namespaces they may work in. A namespace they created has an invite
 * action on its row, opening the dialog that invites another user by name.
 *
 * <p>A token's secret never appears here. It is shown once, in the dialog
 * that follows its creation ({@link #secretView}); the list knows only the
 * start of it, enough to tell two tokens apart.
 *
 * <p>On an installation without authentication the API answers everyone as
 * the dev user, token or not. The page says so, because a token that seems to
 * work there proves nothing about the installation it is meant for.
 */
public class ProfilePage extends AdminPage {

    /** The token table — replaced in place after a token is created or revoked. */
    public static final String TOKENS_ID = "api-tokens";
    /** The namespaces table — replaced in place after an invitation. */
    public static final String NAMESPACES_ID = "profile-namespaces";
    static final String INVITE_FORM_ID = "namespace-invite-form";
    /** The variables table — replaced in place after a variable is added or removed. */
    public static final String ENVIRONMENT_ID = "profile-environment";
    static final String ENVIRONMENT_FORM_ID = "profile-environment-form";
    /** The table of what the installed tools ask for — replaced beside the user's own after every change. */
    public static final String TOOL_VARIABLES_ID = "profile-tool-variables";
    /** The namespace variables tab's content — replaced when a namespace the user created comes or goes. */
    public static final String NAMESPACE_ENVIRONMENT_ID = "profile-namespace-variables";

    /** What a user of an installation without authentication has to know before trusting a token to protect anything. */
    public static final String AUTH_OFF_NOTE = "Authentication is off on this installation: the API answers every "
            + "request as the dev user, with or without a token. A token only protects anything where "
            + "authentication is on (mindconnect.auth.enabled).";

    static final String API = "/admin/api/profile";
    static final String NEW_TOKEN_FORM_ID = "api-token-form";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final UserId userId;
    private final Actor me;
    private final User user;
    private final String displayName;
    private final String email;
    private final List<ApiToken> tokens;
    private final boolean authEnabled;
    private final List<NamespaceDefinition> namespaces;
    private final Namespace defaultNamespace;
    /** Whether the default namespace still takes every signed-in user; see {@code NamespacesPage}. */
    private final boolean defaultOpen;
    private final Namespace active;
    /** What the installed tools declare they need from this user; empty on a host that has no tools. */
    private final List<ToolVariableRow> toolVariables;

    /**
     * @param me          who the signed-in user is to a namespace — id and the address they are
     *                    listed under, which the namespace service answers
     * @param user        the stored user record; null before it was first recorded
     * @param displayName the name the login carries; null to fall back to the record
     * @param email       the e-mail the login carries; null to fall back to the record
     * @param authEnabled whether this installation checks tokens at all
     * @param namespaces  the namespaces the user may work in, the default one first
     * @param active      the namespace the user is in right now
     */
    public ProfilePage(UserId userId, Actor me, User user, String displayName, String email, List<ApiToken> tokens,
                       boolean authEnabled, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                       Namespace active) {
        this(userId, me, user, displayName, email, tokens, authEnabled, namespaces, defaultNamespace, true, active);
    }

    public ProfilePage(UserId userId, Actor me, User user, String displayName, String email, List<ApiToken> tokens,
                       boolean authEnabled, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                       boolean defaultOpen, Namespace active) {
        this(userId, me, user, displayName, email, tokens, authEnabled, namespaces, defaultNamespace,
                defaultOpen, active, List.of());
    }

    /** The page with what the installed tools ask of this user beside their own variables. */
    public ProfilePage(UserId userId, Actor me, User user, String displayName, String email, List<ApiToken> tokens,
                       boolean authEnabled, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                       boolean defaultOpen, Namespace active, List<ToolVariableRow> toolVariables) {
        this.toolVariables = toolVariables == null ? List.of() : List.copyOf(toolVariables);
        this.defaultOpen = defaultOpen;
        this.userId = userId;
        this.me = me;
        this.user = user;
        this.displayName = displayName;
        this.email = email;
        this.tokens = tokens;
        this.authEnabled = authEnabled;
        this.namespaces = namespaces;
        this.defaultNamespace = defaultNamespace;
        this.active = active;
    }

    @Override
    public UiPage render() {
        UiDetail details = UiDetail.of("profile-user", title()).icon("user-round")
                .field(UiField.text("userId", "User id", userId.value()))
                .field(UiField.text("email", "E-mail", orDash(email != null ? email : user == null ? null : user.email())))
                .field(UiField.text("since", "Known since", user == null ? "—" : time(user.createdAt())));
        UiText help = UiText.of("profile-token-help",
                "A program calls the REST API (/api/…, /v1/…) as you with one of your tokens, sent as the "
                + "header \"Authorization: Bearer <token>\". A token's secret is shown once, when it is "
                + "created. Revoke a token you no longer need — programs using it stop working at once.");
        UiStack account = UiStack.of("profile-account").gap(20);
        if (!authEnabled) {
            account.child(authOffNote("profile-auth-off"));
        }
        account.child(details);
        // One tab per concern: the page had grown into a scroll through five
        // unrelated blocks. The tab ids differ from the ids of the tables inside
        // them, because a tab id becomes its panel's DOM id and the tables are
        // replaced in place by id.
        UiSection page = UiSection.of("profile", null)
                .section("profile-tab-account", "Account", account)
                .section("profile-tab-namespaces", "Namespaces",
                        namespaces(me, namespaces, defaultNamespace, defaultOpen, active))
                .section("profile-tab-variables", "Your variables",
                        UiStack.of("profile-variables").gap(20)
                                .child(environment(user == null ? Map.of() : user.environment()))
                                .child(toolVariables(toolVariables)))
                .section("profile-tab-namespace-variables", "Namespace variables",
                        namespaceVariables(me, namespaces, defaultOpen ? defaultNamespace : null))
                .section("profile-tab-tokens", "API tokens", UiStack.of("profile-tokens").gap(12)
                        .child(tokenTable(tokens))
                        .child(help));
        return UiPage.of("/admin/profile", page);
    }

    /**
     * The variables of every namespace the user shapes, one table each — the ones
     * they set for everyone working there. Only a namespace's admins set them, so a
     * namespace they are merely a user of is not listed, and the open default
     * namespace has none of its own. The tables are the namespaces screen's, so adding or removing a
     * variable here goes through the same endpoints and lands on the same ids.
     */
    public static UiNode namespaceVariables(Actor me, List<NamespaceDefinition> namespaces, Namespace defaultNamespace) {
        UiStack stack = UiStack.of(NAMESPACE_ENVIRONMENT_ID).gap(16);
        stack.child(UiText.of(NAMESPACE_ENVIRONMENT_ID + "-note",
                "A ${VAR} in an LLM config takes your own variable first, then the variable of the "
                        + "namespace you work in, then the server's environment. The variables of a namespace "
                        + "are shared with everyone in it; its admins set them."));
        List<NamespaceDefinition> created = namespaces.stream()
                .filter(ns -> !ns.id().equals(defaultNamespace) && ns.isAdmin(me))
                .toList();
        if (created.isEmpty()) {
            stack.child(UiText.of(NAMESPACE_ENVIRONMENT_ID + "-none",
                    "You are not an admin of any namespace, so there are no namespace variables for you to set. "
                            + "Create one from the namespace switcher in the header."));
            return stack;
        }
        for (NamespaceDefinition ns : created) {
            stack.child(NamespacesPage.environment(ns));
        }
        return stack;
    }

    /** The user's tokens, newest first as given, with a revoke action per row. */
    public static UiTable tokenTable(List<ApiToken> tokens) {
        UiTable table = UiTable.of(TOKENS_ID, "API tokens").stackOnMobile(true).icon("key-round")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("hint", "Token"))
                .column(UiTable.Column.text("created", "Created"))
                .column(UiTable.Column.text("lastUsed", "Last used"))
                .column(UiTable.Column.text("expires", "Expires"))
                .action(UiAction.primary("new-token", "New token").icon("add")
                        .dispatch("GET", API + "/tokens/new"))
                .rowAction(UiAction.danger("revoke", "Revoke").icon("delete")
                        .confirm("Revoke this token? Programs using it stop working at once.")
                        .dispatch("DELETE", API + "/tokens/{id}"));
        for (ApiToken token : tokens) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", token.id().value());
            row.put("name", orDash(token.name()));
            row.put("hint", token.hint() == null ? "—" : token.hint() + "…");
            row.put("created", time(token.createdAt()));
            row.put("lastUsed", token.lastUsedAt() == null ? "never" : time(token.lastUsedAt()));
            row.put("expires", token.expiresAt() == null ? "never" : time(token.expiresAt()));
            table.row(row);
        }
        return table;
    }

    public static UiNode namespaces(Actor me, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                                    Namespace active) {
        return namespaces(me, namespaces, defaultNamespace, true, active);
    }

    /**
     * The user's namespaces: where they may work, with a way to switch there and,
     * on every row, leave and delete — the controller answers each with the deed
     * where the user may and with the reason where not.
     *
     * <p><strong>Inviting is offered only to somebody who shapes one of them.</strong>
     * A row action is on every row or on none, so a user of every namespace they
     * are in gets no invite button at all rather than one that always answers
     * "only an admin may".
     *
     * <p>The default namespace is listed like the others; while nobody has been
     * named to shape it, it is the one everybody is in and nobody is invited to.
     */
    public static UiNode namespaces(Actor me, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                                    boolean defaultOpen, Namespace active) {
        boolean shapesOne = namespaces.stream()
                .anyMatch(ns -> ns.isAdmin(me) && !(ns.id().equals(defaultNamespace) && defaultOpen));
        UiTable table = UiTable.of(NAMESPACES_ID, "Namespaces").stackOnMobile(true).icon("layers")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("namespace", "Id"))
                .column(UiTable.Column.text("role", "Your role"))
                .column(UiTable.Column.text("members", "Members"))
                .column(UiTable.Column.text("created", "Created"))
                .rowAction(UiAction.secondary("switch", "Switch to").icon("arrow-right")
                        .dispatch("POST", NamespacesPage.API + "/switch/{id}"))
                .rowAction(UiAction.secondary("leave", "Leave").icon("log-out")
                        .confirm("Leave this namespace? You will need a new invitation to come back.")
                        .dispatch("POST", API + "/namespaces/{id}/leave"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this namespace with everything in it — agents, sessions, files, workflows, "
                                + "vector stores, MCP servers? This cannot be undone.")
                        .dispatch("DELETE", API + "/namespaces/{id}"));
        if (shapesOne) {
            table.rowAction(UiAction.secondary("invite", "Invite…").icon("user-plus")
                    .dispatch("GET", API + "/namespaces/{id}/invite"));
        }
        for (NamespaceDefinition ns : namespaces) {
            boolean isDefault = ns.id().equals(defaultNamespace) && defaultOpen;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ns.id().value());
            row.put("name", ns.label() + (ns.id().equals(active) ? " (current)" : ""));
            row.put("namespace", ns.id().value());
            row.put("role", isDefault ? "everyone" : role(ns, me));
            row.put("members", isDefault ? "every signed-in user"
                    : String.valueOf(ns.admins().size() + ns.users().size()));
            row.put("created", time(ns.createdAt()));
            table.row(row);
        }
        return table;
    }

    /** What the signed-in user is in {@code ns}: its creator, an admin they promoted, or a user. */
    private static String role(NamespaceDefinition ns, Actor me) {
        if (ns.isCreator(me)) return "creator";
        return ns.role(me).map(r -> r == NamespaceRole.ADMIN ? "admin" : "user").orElse("—");
    }

    /**
     * The user's own variables: what {@code ${VAR}} placeholders in LLM configs — an API key —
     * resolve to for them, before the namespace's and the server's values. Names only; a
     * value is a secret and is never shown again.
     */
    public static UiNode environment(Map<String, String> environment) {
        return NamespacesPage.environmentTable(ENVIRONMENT_ID, "Your variables", environment.keySet(),
                API + "/environment",
                "Remove this variable? A config that refers to it falls back to the namespace's or the server's value.");
    }

    /** The body of the "Add variable" dialog; {@code error} keeps it open with the reason. */
    /**
     * One declared variable as this user stands to it: what the tools call it,
     * and whether anybody has a value for it.
     *
     * @param variable     the declaration, as the tool source wrote it
     * @param setByUser    true when the user has a variable of that name of their own
     * @param fromElsewhere true when the namespace or the server answers it — then
     *                      there is nothing for the user to do, and saying so is the
     *                      difference between "not set" and "not yours to set"
     */
    public record ToolVariableRow(ToolVariable variable, boolean setByUser, boolean fromElsewhere) {

        /** Set by the user, or by somebody on their behalf. */
        public boolean satisfied() {
            return setByUser || fromElsewhere;
        }
    }

    /**
     * What the installed tools ask for. It is the answer to a question nobody
     * can answer alone: a user cannot know that the mail tools look up
     * {@code MC_EMAIL_HOST}, and a list of names with an empty column beside
     * them is exactly that knowledge.
     *
     * <p>A variable with a sensible default is already in the table above by
     * the time this is rendered — it was written on sign-in. What is left here
     * is what nobody could have guessed: an address, an account, a password.
     */
    public static UiNode toolVariables(List<ToolVariableRow> rows) {
        UiStack stack = UiStack.of(TOOL_VARIABLES_ID).gap(12);
        if (rows.isEmpty()) {
            stack.child(UiText.of(TOOL_VARIABLES_ID + "-none",
                    "No installed tool asks for a variable of yours."));
            return stack;
        }
        stack.child(UiText.of(TOOL_VARIABLES_ID + "-note",
                "The tools this installation offers look up these variables when they run for you. "
                        + "A missing one is only missing for you — the tool itself is installed and "
                        + "works for everyone who has filled it in."));
        UiTable table = UiTable.of(TOOL_VARIABLES_ID + "-table", "What the tools need").stackOnMobile(true)
                .icon("settings")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("what", "What it is"))
                .column(UiTable.Column.text("tool", "Used by"))
                .column(UiTable.Column.text("status", "Status"))
                .rowAction(UiAction.secondary("set", "Set").icon("edit")
                        .dispatch("GET", API + "/environment/new/{id}"));
        for (ToolVariableRow row : rows) {
            ToolVariable variable = row.variable();
            Map<String, Object> cells = new LinkedHashMap<>();
            cells.put("id", variable.name());
            cells.put("name", variable.name());
            cells.put("what", variable.title());
            cells.put("tool", orDash(variable.declaredBy()));
            cells.put("status", status(row));
            table.row(cells);
        }
        stack.child(table);
        return stack;
    }

    private static String status(ToolVariableRow row) {
        if (row.setByUser()) return "set by you";
        if (row.fromElsewhere()) return "from the namespace or the server";
        return row.variable().required() ? "required — not set" : "optional — not set";
    }

    /**
     * The add-a-variable form with a declared variable already named, so the
     * user only has to bring the value. The name stays editable: a form that
     * cannot be corrected is worse than one that can be got wrong.
     */
    public static UiForm environmentForm(ToolVariable variable, String error) {
        UiForm form = UiForm.of(ENVIRONMENT_FORM_ID, null)
                .field(UiField.text("name", "Name", variable.name()).asEditable().asRequired()
                        .hint("As a tool refers to it: ${" + variable.name() + "}."))
                .field(value(variable))
                .action(UiAction.primary(ENVIRONMENT_FORM_ID + "-save", "Save").icon("key-round")
                        .dispatch("POST", API + "/environment", ENVIRONMENT_FORM_ID))
                .action(UiAction.secondary(ENVIRONMENT_FORM_ID + "-cancel", "Cancel")
                        .dispatch("POST", API + "/environment/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    /** A secret is masked and never shown again; a host or a port is plain text, because a typo has to be visible. */
    private static UiField value(ToolVariable variable) {
        String hint = variable.description() != null && !variable.description().isBlank()
                ? variable.description()
                : "The value the " + orDash(variable.declaredBy()) + " tools use for " + variable.title() + ".";
        UiField field = variable.secret()
                ? UiField.password("value", variable.title(), null)
                : UiField.text("value", variable.title(), variable.defaultValue());
        return field.asEditable().asRequired()
                .hint(variable.secret() ? hint + " Stored encrypted and never shown again." : hint);
    }

    public static UiForm environmentForm(String error) {
        return NamespacesPage.environmentForm(ENVIRONMENT_FORM_ID, error, API + "/environment",
                API + "/environment/dialog/close");
    }

    /** The body of the invite dialog for {@code ns}; {@code error} keeps it open with the reason. */
    public static UiForm inviteForm(NamespaceDefinition ns, String error) {
        String id = ns.id().value();
        return NamespacesPage.inviteForm(INVITE_FORM_ID, null, id, error, API + "/namespaces/" + id + "/members")
                .action(UiAction.secondary("cancel", "Cancel")
                        .dispatch("POST", API + "/namespaces/dialog/close"));
    }

    /** The form of the "New token" dialog; {@code error} is shown above the fields when set. */
    public static UiForm newTokenForm(String error) {
        UiForm form = UiForm.of(NEW_TOKEN_FORM_ID, null)
                .field(UiField.text("name", "Name", null).asEditable().asRequired()
                        .placeholder("e.g. laptop, CI")
                        .hint("What the token is for — it names the token in the list."))
                .field(UiField.select("expiresIn", "Expires", "90", List.of(
                                UiField.Option.of("30", "in 30 days"),
                                UiField.Option.of("90", "in 90 days"),
                                UiField.Option.of("365", "in a year"),
                                UiField.Option.of("never", "never")))
                        .asEditable())
                .action(UiAction.primary("create", "Create token").icon("key-round")
                        .dispatch("POST", API + "/tokens", NEW_TOKEN_FORM_ID))
                .action(UiAction.secondary("cancel", "Cancel")
                        .dispatch("POST", API + "/tokens/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    /**
     * The one view of a new token's secret: the value to copy, and a request
     * that uses it against this installation — with the warning that the
     * request proves nothing while authentication is off.
     */
    public static UiNode secretView(String secret, String baseUrl, boolean authEnabled) {
        // An input rather than text, so the value can be selected and copied by
        // hand; the Copy button beside it puts it on the clipboard in one click
        // (the browser-side "mc-copy-field" handler — semantic-ui has no
        // clipboard behaviour of its own).
        UiForm form = UiForm.of("api-token-secret", null)
                .field(UiField.text("secret", "Your new token", secret).asEditable()
                        .trailing(UiAction.secondary("copy-secret", "Copy").icon("copy")
                                .onClick(UiTrigger.invoke("mc-copy-field")))
                        .hint("Copy it now. It is shown only this once and cannot be displayed again."))
                .content(UiText.of("api-token-example",
                        "curl -H \"Authorization: Bearer " + secret + "\" " + baseUrl + "/api/agents"));
        if (!authEnabled) {
            form.content(authOffNote("api-token-auth-off"));
        }
        return form.action(UiAction.primary("done", "Done")
                .dispatch("POST", API + "/tokens/dialog/close"));
    }

    private static UiNode authOffNote(String id) {
        return UiText.of(id, "⚠ " + AUTH_OFF_NOTE).withCssClass("task-card-body");
    }

    private String title() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return user != null ? user.label() : userId.value();
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
