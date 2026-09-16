package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
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
    private final User user;
    private final String displayName;
    private final String email;
    private final List<ApiToken> tokens;
    private final boolean authEnabled;
    private final List<NamespaceDefinition> namespaces;
    private final Namespace defaultNamespace;
    private final Namespace active;

    /**
     * @param user        the stored user record; null before it was first recorded
     * @param displayName the name the login carries; null to fall back to the record
     * @param email       the e-mail the login carries; null to fall back to the record
     * @param authEnabled whether this installation checks tokens at all
     * @param namespaces  the namespaces the user may work in, the default one first
     * @param active      the namespace the user is in right now
     */
    public ProfilePage(UserId userId, User user, String displayName, String email, List<ApiToken> tokens,
                       boolean authEnabled, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                       Namespace active) {
        this.userId = userId;
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
                .section("profile-tab-namespaces", "Namespaces", namespaces(userId, namespaces, defaultNamespace, active))
                .section("profile-tab-variables", "Your variables", environment(user == null ? Map.of() : user.environment()))
                .section("profile-tab-namespace-variables", "Namespace variables",
                        namespaceVariables(userId, namespaces, defaultNamespace))
                .section("profile-tab-tokens", "API tokens", UiStack.of("profile-tokens").gap(12)
                        .child(tokenTable(tokens))
                        .child(help));
        return UiPage.of("/admin/profile", page);
    }

    /**
     * The variables of every namespace the user created, one table each — the ones they
     * set for everyone working there. Only a namespace's creator sets them, so a
     * namespace they merely joined is not listed, and the open default namespace has
     * none of its own. The tables are the namespaces screen's, so adding or removing a
     * variable here goes through the same endpoints and lands on the same ids.
     */
    public static UiNode namespaceVariables(UserId me, List<NamespaceDefinition> namespaces, Namespace defaultNamespace) {
        UiStack stack = UiStack.of(NAMESPACE_ENVIRONMENT_ID).gap(16);
        stack.child(UiText.of(NAMESPACE_ENVIRONMENT_ID + "-note",
                "A ${VAR} in an LLM config takes your own variable first, then the variable of the "
                        + "namespace you work in, then the server's environment. The variables of a namespace "
                        + "are shared with everyone in it; only the one who created the namespace sets them."));
        List<NamespaceDefinition> created = namespaces.stream()
                .filter(ns -> !ns.id().equals(defaultNamespace) && ns.isCreator(me))
                .toList();
        if (created.isEmpty()) {
            stack.child(UiText.of(NAMESPACE_ENVIRONMENT_ID + "-none",
                    "You have not created a namespace, so there are no namespace variables for you to set. "
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
        UiTable table = UiTable.of(TOKENS_ID, "API tokens").icon("key-round")
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

    /**
     * The user's namespaces: where they may work, with a way to switch there and, on
     * every row, the invite, leave and delete actions — the controller answers each
     * with the dialog or the deed where the user may, and with the reason where not. The open default
     * namespace is listed like the others; nobody is invited into it.
     */
    public static UiNode namespaces(UserId me, List<NamespaceDefinition> namespaces, Namespace defaultNamespace,
                                    Namespace active) {
        UiTable table = UiTable.of(NAMESPACES_ID, "Namespaces").icon("layers")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("namespace", "Id"))
                .column(UiTable.Column.text("role", "Your role"))
                .column(UiTable.Column.text("members", "Members"))
                .column(UiTable.Column.text("created", "Created"))
                .rowAction(UiAction.secondary("switch", "Switch to").icon("arrow-right")
                        .dispatch("POST", NamespacesPage.API + "/switch/{id}"))
                .rowAction(UiAction.secondary("invite", "Invite…").icon("user-plus")
                        .dispatch("GET", API + "/namespaces/{id}/invite"))
                .rowAction(UiAction.secondary("leave", "Leave").icon("log-out")
                        .confirm("Leave this namespace? You will need a new invitation to come back.")
                        .dispatch("POST", API + "/namespaces/{id}/leave"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this namespace with everything in it — agents, sessions, files, workflows, "
                                + "vector stores, MCP servers? This cannot be undone.")
                        .dispatch("DELETE", API + "/namespaces/{id}"));
        for (NamespaceDefinition ns : namespaces) {
            boolean isDefault = ns.id().equals(defaultNamespace);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ns.id().value());
            row.put("name", ns.label() + (ns.id().equals(active) ? " (current)" : ""));
            row.put("namespace", ns.id().value());
            row.put("role", isDefault ? "everyone" : ns.isCreator(me) ? "creator" : "member");
            row.put("members", isDefault ? "every signed-in user" : String.valueOf(ns.members().size()));
            row.put("created", time(ns.createdAt()));
            table.row(row);
        }
        return table;
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
