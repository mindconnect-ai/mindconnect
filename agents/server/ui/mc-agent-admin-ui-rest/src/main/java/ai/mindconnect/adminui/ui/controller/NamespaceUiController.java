package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.NamespaceMembers;
import ai.mindconnect.adminui.ui.page.NamespacesPage;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.agent.starter.namespace.NamespaceSelection;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
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

import java.net.URI;
import java.util.Map;

/**
 * The namespaces screen and the header switcher's endpoints: switch, create,
 * invite (by e-mail address, in a role), promote, demote, remove. Everything acts as the signed-in user — what they may
 * switch to, invite into or change is the {@link NamespaceService}'s answer,
 * not a permission of the screen.
 *
 * <p>Switching and creating end in a redirect to the agents list rather than
 * a patch: the whole shell has to be rendered again in the new namespace,
 * and the SPA follows the redirect to a fresh page.
 */
@RestController
@RequestMapping(NamespacesPage.API)
public class NamespaceUiController {

    static final String CREATE_DIALOG_ID = "namespace-create-dialog";
    static final String ENVIRONMENT_DIALOG_ID = "namespace-environment-dialog";
    static final String ENVIRONMENT_FORM_ID = "namespace-environment-form";
    /** Where a switch lands: the agents of the namespace just entered. */
    static final URI AFTER_SWITCH = URI.create("/admin/api/agents");

    private final NamespaceService namespaces;
    private final UserRepository users;
    private final UserService userService;
    private final NamespaceMembers members;
    private final ScopeSupplier scope;

    public NamespaceUiController(NamespaceService namespaces, UserRepository users, UserService userService,
                                 NamespaceMembers members, ScopeSupplier scope) {
        this.namespaces = namespaces;
        this.users = users;
        this.userService = userService;
        this.members = members;
        this.scope = scope;
    }

    @GetMapping
    public UiPage page(@AuthenticationPrincipal OidcUser user) {
        return page(userId(user));
    }

    /** Enters {@code id} for the rest of the browser session — a POST: it changes what the user works in. */
    @PostMapping("/switch/{id}")
    public ResponseEntity<Void> switchTo(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                                         HttpServletRequest request) {
        UserId me = userId(user);
        Namespace target;
        try {
            target = new Namespace(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (!namespaces.canAccess(me, target)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        enter(request, me, target);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(AFTER_SWITCH).build();
    }

    /** Opens the "New namespace" dialog. */
    @GetMapping("/new")
    public UiPatch newNamespace() {
        return dialog(CREATE_DIALOG_ID, "New namespace", NamespacesPage.createForm(null));
    }

    /** Creates the namespace, makes it the session's, and lands on its (empty) agents list. */
    @PostMapping
    public ResponseEntity<Object> create(@AuthenticationPrincipal OidcUser user, @RequestBody Map<String, Object> raw,
                                         HttpServletRequest request) {
        FormBody body = new FormBody(raw);
        NamespaceDefinition created;
        try {
            created = namespaces.create(body.str("id"), body.str("displayName"), userId(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(dialog(CREATE_DIALOG_ID, "New namespace", NamespacesPage.createForm(e.getMessage())));
        }
        enter(request, userId(user), created.id());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(AFTER_SWITCH).build();
    }

    @PostMapping("/dialog/close")
    public UiPatch closeDialog() {
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(CREATE_DIALOG_ID))
                .patch(UiPatch.Operation.remove(ENVIRONMENT_DIALOG_ID));
    }

    /** Opens the "Add variable" dialog for {@code id}; its admins set variables, anyone else is told so. */
    @GetMapping("/{id}/environment/new")
    public UiPatch newVariable(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        return namespaces.find(namespace)
                .filter(ns -> ns.isAdmin(namespaces.actor(me)))
                .map(ns -> dialog(ENVIRONMENT_DIALOG_ID, "Add variable to " + ns.label(), environmentForm(id, null)))
                .orElseGet(() -> UiPatch.of().toast(UiToast.error("Only an admin of '" + id + "' sets its variables.")
                        .title("Not yours to configure")));
    }

    /**
     * Adds the variable — or replaces the value of one with that name — closes the dialog
     * and re-renders the namespace's variables table. A refused name keeps the dialog open
     * with the reason.
     */
    @PostMapping("/{id}/environment")
    public UiPatch addVariable(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                               @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        FormBody body = new FormBody(raw);
        String name = body.str("name") == null ? null : body.str("name").strip();
        NamespaceDefinition updated;
        try {
            updated = namespaces.putVariable(namespace, me, name, body.str("value"));
        } catch (IllegalArgumentException e) {
            return dialog(ENVIRONMENT_DIALOG_ID, "Add variable to " + id, environmentForm(id, e.getMessage()));
        }
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(ENVIRONMENT_DIALOG_ID))
                .patch(UiPatch.Operation.replace(NamespacesPage.environmentId(namespace), NamespacesPage.environment(updated)))
                .toast(UiToast.success("Configs in '" + id + "' referring to ${" + name + "} now use this value.").title("Variable saved"));
    }

    @DeleteMapping("/{id}/environment/{name}")
    public UiPatch removeVariable(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                                  @PathVariable("name") String name) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        NamespaceDefinition updated;
        try {
            updated = namespaces.removeVariable(namespace, me, name).orElse(null);
        } catch (IllegalArgumentException e) {
            return UiPatch.of().toast(UiToast.error(e.getMessage()).title("Not removed"));
        }
        if (updated == null) {
            return UiPatch.of().toast(UiToast.error("'" + id + "' has no variable called " + name + ".").title("Nothing removed"));
        }
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(NamespacesPage.environmentId(namespace), NamespacesPage.environment(updated)))
                .toast(UiToast.success("Configs in '" + id + "' referring to ${" + name + "} fall back to the server's value.").title("Variable removed"));
    }

    private static UiForm environmentForm(String id, String error) {
        return NamespacesPage.environmentForm(ENVIRONMENT_FORM_ID, error,
                NamespacesPage.API + "/" + id + "/environment", NamespacesPage.API + "/dialog/close");
    }

    @PostMapping("/{id}/members")
    public UiPage invite(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                         @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        FormBody body = new FormBody(raw);
        ai.mindconnect.agent.Email invitee;
        NamespaceRole role;
        try {
            role = NamespaceRole.of(body.str("role"));
            invitee = members.invite(new Namespace(id), me, body.str("email"), role);
        } catch (IllegalArgumentException e) {
            return page(me).toast(UiToast.error(e.getMessage()).title("Not invited"));
        }
        String may = role == NamespaceRole.ADMIN ? "may now shape" : "may now work in";
        return page(me).toast(UiToast.success(invitee + " " + may + " '" + id + "'.").title("Invited"));
    }

    @DeleteMapping("/{id}/members/{member}")
    public UiPage remove(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                         @PathVariable("member") String member) {
        UserId me = userId(user);
        try {
            members.remove(new Namespace(id), me, member);
        } catch (IllegalArgumentException e) {
            return page(me).toast(UiToast.error(e.getMessage()).title("Not removed"));
        }
        return page(me).toast(UiToast.success(member + " is no longer in '" + id + "'.").title("Removed"));
    }

    /** Makes {@code member} an admin of {@code id} — they shape it from now on, except deleting it. */
    @PostMapping("/{id}/members/{member}/promote")
    public UiPage promote(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                          @PathVariable("member") String member) {
        UserId me = userId(user);
        try {
            members.promote(new Namespace(id), me, member);
        } catch (IllegalArgumentException e) {
            return page(me).toast(UiToast.error(e.getMessage()).title("Not promoted"));
        }
        return page(me).toast(UiToast.success(member + " shapes '" + id + "' now.").title("Admin"));
    }

    /** Makes {@code member} a user of {@code id} again; the creator stays an admin. */
    @PostMapping("/{id}/members/{member}/demote")
    public UiPage demote(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                         @PathVariable("member") String member) {
        UserId me = userId(user);
        try {
            members.demote(new Namespace(id), me, member);
        } catch (IllegalArgumentException e) {
            return page(me).toast(UiToast.error(e.getMessage()).title("Not demoted"));
        }
        return page(me).toast(UiToast.success(member + " uses '" + id + "' now — chat and running workflows.")
                .title("User"));
    }

    /** The caller leaves {@code id}; leaving the current namespace lands in the default one. */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Object> leave(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                                        HttpServletRequest request) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        try {
            members.leave(namespace, me);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(page(me).toast(UiToast.error(e.getMessage()).title("Not left")));
        }
        return afterLeaving(request, me, namespace,
                UiToast.success("You are no longer a member of '" + id + "'.").title("Left"));
    }

    /** Deletes {@code id} with everything in it; only its creator may. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                                         HttpServletRequest request) {
        UserId me = userId(user);
        Namespace namespace = new Namespace(id);
        try {
            members.delete(namespace, me);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(page(me).toast(UiToast.error(e.getMessage()).title("Not deleted")));
        }
        return afterLeaving(request, me, namespace,
                UiToast.success("Namespace '" + id + "' and everything in it are gone.").title("Deleted"));
    }

    private ResponseEntity<Object> afterLeaving(HttpServletRequest request, UserId me, Namespace left, UiToast toast) {
        if (left.equals(scope.namespace())) {
            enter(request, me, namespaces.defaultNamespace());
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(AFTER_SWITCH).build();
        }
        return ResponseEntity.ok(page(me).toast(toast));
    }

    /** For this session, and remembered on the user for the next one and the next restart. */
    private void enter(HttpServletRequest request, UserId me, Namespace namespace) {
        NamespaceSelection.select(request, namespace);
        userService.selectNamespace(me, namespace);
    }

    private UiPage page(UserId me) {
        return new NamespacesPage(namespaces.actor(me), scope.namespace(), namespaces.defaultNamespace(),
                namespaces.defaultIsOpen(), namespaces.forUser(me), users.findAll()).render();
    }


    private static UserId userId(OidcUser user) {
        return UserId.of(SessionOwnership.userIdOf(user));
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
