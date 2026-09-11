package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.ProfilePage;
import ai.mindconnect.agent.UserId;
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
 * The profile page behind the header avatar, and the dialogs that issue and
 * revoke the signed-in user's API tokens. Everything here acts on the caller's
 * own tokens only — the token id in a revoke is checked against the owner, and
 * a foreign id is answered like a missing one.
 */
@RestController
@RequestMapping("/admin/api/profile")
public class ProfileUiController {

    static final String CREATE_DIALOG_ID = "api-token-create-dialog";
    static final String SECRET_DIALOG_ID = "api-token-secret-dialog";

    private static final Duration DEFAULT_LIFETIME = Duration.ofDays(90);

    private final ApiTokenService tokens;
    private final UserService users;
    private final Clock clock;
    private final boolean authEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileUiController(ApiTokenService tokens, UserService users,
                               @org.springframework.beans.factory.annotation.Value("${mindconnect.auth.enabled:false}")
                               boolean authEnabled) {
        this(tokens, users, Clock.systemUTC(), authEnabled);
    }

    ProfileUiController(ApiTokenService tokens, UserService users, Clock clock, boolean authEnabled) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
        this.authEnabled = authEnabled;
    }

    @GetMapping
    public UiPage profile(@AuthenticationPrincipal OidcUser user) {
        UserId id = userId(user);
        return new ProfilePage(id, users.find(id).orElse(null),
                user == null ? null : user.getFullName(),
                user == null ? null : user.getEmail(),
                tokens.list(id), authEnabled).render();
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
