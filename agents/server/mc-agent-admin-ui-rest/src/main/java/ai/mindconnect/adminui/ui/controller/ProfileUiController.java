package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.NamespaceMembers;
import ai.mindconnect.adminui.ui.page.ProfilePage;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The profile page behind the header avatar, the dialogs that issue and
 * revoke the signed-in user's API tokens, and the members into the
 * namespaces they created. Everything here acts on the caller's
 * own tokens only — the token id in a revoke is checked against the owner, and
 * a foreign id is answered like a missing one.
 */
@RestController
@RequestMapping("/admin/api/profile")
public class ProfileUiController {

    static final String CREATE_DIALOG_ID = "api-token-create-dialog";
    static final String SECRET_DIALOG_ID = "api-token-secret-dialog";
    static final String INVITE_DIALOG_ID = "namespace-invite-dialog";

    private static final Duration DEFAULT_LIFETIME = Duration.ofDays(90);

    private final ApiTokenService tokens;
    private final UserService users;
    private final NamespaceService namespaces;
    private final NamespaceMembers members;
    private final ScopeSupplier scope;
    private final Clock clock;
    private final boolean authEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileUiController(ApiTokenService tokens, UserService users, NamespaceService namespaces,
                               NamespaceMembers members, ScopeSupplier scope,
                               @org.springframework.beans.factory.annotation.Value("${mindconnect.auth.enabled:false}")
                               boolean authEnabled) {
        this(tokens, users, namespaces, members, scope, Clock.systemUTC(), authEnabled);
    }

    ProfileUiController(ApiTokenService tokens, UserService users, NamespaceService namespaces,
                        NamespaceMembers members, ScopeSupplier scope, Clock clock, boolean authEnabled) {
        this.tokens = tokens;
        this.users = users;
        this.namespaces = namespaces;
        this.members = members;
        this.scope = scope;
        this.clock = clock;
        this.authEnabled = authEnabled;
    }

    @GetMapping
    public UiPage profile(@AuthenticationPrincipal OidcUser user) {
        UserId id = userId(user);
        return new ProfilePage(id, users.find(id).orElse(null),
                user == null ? null : user.getFullName(),
                user == null ? null : user.getEmail(),
                tokens.list(id), authEnabled,
                namespaces.forUser(id), namespaces.defaultNamespace(), scope.namespace()).render();
    }

    /**
     * Opens the invite dialog for one of the caller's namespaces. The action sits on every
     * row, so a namespace the caller did not create — or the open default one — is
     * answered with the reason instead of a dialog.
     */
    @GetMapping("/namespaces/{id}/invite")
    public UiPatch inviteDialog(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        if (namespace.equals(namespaces.defaultNamespace())) {
            return UiPatch.of().toast(UiToast.info("The default namespace is open to every signed-in user.")
                    .title("Nobody to invite"));
        }
        return namespaces.find(namespace)
                .filter(ns -> ns.isCreator(me))
                .map(ns -> dialog(INVITE_DIALOG_ID, "Invite into " + ns.label(), ProfilePage.inviteForm(ns, null)))
                .orElseGet(() -> UiPatch.of().toast(UiToast.error("Only the creator of '" + id + "' invites.")
                        .title("Not yours to invite into")));
    }

    /**
     * Invites the user named in the dialog, closes it and re-renders the namespaces
     * table. A name nobody signed in with keeps the dialog open and says why.
     */
    @PostMapping("/namespaces/{id}/members")
    public UiPatch invite(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                          @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        String invitee;
        try {
            invitee = members.invite(namespace, me, new FormBody(raw).str("user")).label();
        } catch (IllegalArgumentException e) {
            return namespaces.find(namespace)
                    .filter(ns -> ns.isCreator(me))
                    .map(ns -> dialog(INVITE_DIALOG_ID, "Invite into " + ns.label(), ProfilePage.inviteForm(ns, e.getMessage())))
                    .orElseGet(() -> UiPatch.of().patch(UiPatch.Operation.remove(INVITE_DIALOG_ID))
                            .toast(UiToast.error(e.getMessage()).title("Not invited")));
        }
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(INVITE_DIALOG_ID))
                .patch(UiPatch.Operation.replace(ProfilePage.NAMESPACES_ID, ProfilePage.namespaces(me,
                        namespaces.forUser(me), namespaces.defaultNamespace(), scope.namespace())))
                .toast(UiToast.success(invitee + " may now work in '" + id + "'.").title("Invited"));
    }

    /**
     * The caller leaves one of their namespaces. Leaving the one they are in lands them
     * in the default namespace, through the switch — the whole shell has to be rendered
     * again there.
     */
    @PostMapping("/namespaces/{id}/leave")
    public ResponseEntity<Object> leave(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        try {
            members.leave(namespace, me);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(UiPatch.of().toast(UiToast.error(e.getMessage()).title("Not left")));
        }
        return afterLeaving(me, namespace, "You are no longer a member of '" + id + "'.", "Left");
    }

    /** Deletes one of the caller's namespaces with everything in it; only its creator may. */
    @DeleteMapping("/namespaces/{id}")
    public ResponseEntity<Object> delete(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        try {
            members.delete(namespace, me);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(UiPatch.of().toast(UiToast.error(e.getMessage()).title("Not deleted")));
        }
        return afterLeaving(me, namespace, "Namespace '" + id + "' and everything in it are gone.", "Deleted");
    }

    /**
     * Out of the current namespace: the default one becomes the remembered choice and the
     * shell is rendered again there (the binding filter drops a session choice the user
     * may no longer use and falls back to the remembered one); out of another: refresh
     * the table.
     */
    private ResponseEntity<Object> afterLeaving(UserId me, Namespace left, String message, String title) {
        if (left.equals(scope.namespace())) {
            users.selectNamespace(me, namespaces.defaultNamespace());
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(NamespaceUiController.AFTER_SWITCH).build();
        }
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.replace(ProfilePage.NAMESPACES_ID, ProfilePage.namespaces(me,
                        namespaces.forUser(me), namespaces.defaultNamespace(), scope.namespace())))
                .toast(UiToast.success(message).title(title)));
    }

    @PostMapping("/namespaces/dialog/close")
    public UiPatch closeInviteDialog() {
        return UiPatch.of().patch(UiPatch.Operation.remove(INVITE_DIALOG_ID));
    }

    /** Opens the "New token" dialog. */
    @GetMapping("/tokens/new")
    public UiPatch newToken() {
        return dialog(CREATE_DIALOG_ID, "New API token", ProfilePage.newTokenForm(null));
    }

    /**
     * Issues the token, refreshes the list, and swaps the form dialog for the
     * one that shows the secret. A refused name or expiry keeps the form open
     * with the reason.
     */
    @PostMapping("/tokens")
    public UiPatch create(@AuthenticationPrincipal OidcUser user, @RequestBody Map<String, Object> raw) {
        UserId id = userId(user);
        FormBody body = new FormBody(raw);
        ApiTokenService.Issued issued;
        try {
            issued = tokens.issue(id, body.str("name"), expiry(body.str("expiresIn")));
        } catch (IllegalArgumentException e) {
            return dialog(CREATE_DIALOG_ID, "New API token", ProfilePage.newTokenForm(e.getMessage()));
        }
        return dialog(SECRET_DIALOG_ID, "API token \"" + issued.token().name() + "\" created",
                        ProfilePage.secretView(issued.secret(), baseUrl(), authEnabled))
                .patch(UiPatch.Operation.remove(CREATE_DIALOG_ID))
                .patch(UiPatch.Operation.replace(ProfilePage.TOKENS_ID, ProfilePage.tokenTable(tokens.list(id))));
    }

    /** Closes whichever token dialog is open; the page behind stays as it is. */
    @PostMapping("/tokens/dialog/close")
    public UiPatch closeDialog() {
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(CREATE_DIALOG_ID))
                .patch(UiPatch.Operation.remove(SECRET_DIALOG_ID));
    }

    @DeleteMapping("/tokens/{id}")
    public UiPatch revoke(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String tokenId) {
        UserId id = userId(user);
        boolean revoked;
        try {
            revoked = tokens.revoke(id, ApiTokenId.of(tokenId));
        } catch (IllegalArgumentException e) {
            revoked = false;
        }
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(ProfilePage.TOKENS_ID, ProfilePage.tokenTable(tokens.list(id))))
                .toast(revoked
                        ? UiToast.success("Programs using it can no longer sign in.").title("Token revoked")
                        : UiToast.error("There is no such token of yours.").title("Nothing revoked"));
    }

    /** {@code never} → no expiry; a number of days; anything else → the default lifetime. */
    Instant expiry(String choice) {
        if ("never".equals(choice)) {
            return null;
        }
        Duration lifetime = DEFAULT_LIFETIME;
        try {
            int days = Integer.parseInt(choice);
            if (days > 0) {
                lifetime = Duration.ofDays(days);
            }
        } catch (NumberFormatException ignored) {
            // an unknown choice gets the default
        }
        return clock.instant().plus(lifetime);
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(SessionOwnership.userIdOf(user));
    }

    /** Where the example request in the secret dialog points: this installation, as the browser reached it. */
    private static String baseUrl() {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        } catch (IllegalStateException outsideARequest) {
            return "https://<host>";
        }
    }

    /** A dialog as a remove+append patch on the body-level dialog host, so a re-render replaces it in place. */
    private static UiPatch dialog(String id, String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(id);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(id))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }
}
