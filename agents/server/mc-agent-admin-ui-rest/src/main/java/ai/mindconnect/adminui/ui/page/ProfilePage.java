package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
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
 * tokens they issued — the way a program calls the REST API as them.
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

    /**
     * @param user        the stored user record; null before it was first recorded
     * @param displayName the name the login carries; null to fall back to the record
     * @param email       the e-mail the login carries; null to fall back to the record
     * @param authEnabled whether this installation checks tokens at all
     */
    public ProfilePage(UserId userId, User user, String displayName, String email, List<ApiToken> tokens,
                       boolean authEnabled) {
        this.userId = userId;
        this.user = user;
        this.displayName = displayName;
        this.email = email;
        this.tokens = tokens;
        this.authEnabled = authEnabled;
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
        UiStack page = UiStack.of("profile").gap(20);
        if (!authEnabled) {
            page.child(authOffNote("profile-auth-off"));
        }
        page.child(details)
                .child(tokenTable(tokens))
                .child(help);
        return UiPage.of("/admin/profile", page);
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
