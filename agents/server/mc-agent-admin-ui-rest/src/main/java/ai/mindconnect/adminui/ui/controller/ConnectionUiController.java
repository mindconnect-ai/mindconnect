package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.setup.ToolConnections;
import ai.mindconnect.adminui.ui.component.ConnectionsComponent;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Acquisition;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Attaching and detaching the signed-in user's own accounts.
 *
 * <p>Everything here acts on the caller's own connections: a connection id
 * that is not theirs is answered like one that does not exist. Nothing is
 * rendered by a tool — the dialog is built from the provider's
 * {@link Schema}, so a new provider costs no work here.
 */
@RestController
@RequestMapping(ConnectionsComponent.API)
public class ConnectionUiController {

    static final String DIALOG_ID = "connection-dialog";

    private final ToolConnections connections;

    public ConnectionUiController(ToolConnections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Opens the form for a new account of this provider. */
    @GetMapping("/{provider}/new")
    public UiPatch newConnection(@PathVariable("provider") String provider) {
        return connections.spec(provider)
                .map(spec -> dialog("Connect " + spec.title(),
                        ConnectionsComponent.form(spec, null, null)))
                .orElseGet(() -> UiPatch.of().toast(unknownProvider(provider)));
    }

    /** Says why an OAuth button does nothing yet, rather than failing silently. */
    @GetMapping("/{provider}/unavailable")
    public UiPatch unavailable(@PathVariable("provider") String provider) {
        return UiPatch.of().toast(UiToast.info(
                        "Connecting this one through the provider's own login is not wired up yet. "
                                + "Use \"Add manually\" in the meantime.")
                .title("Not available yet"));
    }

    /** Opens the form for an account the user already has. */
    @GetMapping("/{id}/edit")
    public UiPatch edit(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        return found(me, id)
                .map(connection -> connections.spec(connection.provider())
                        .map(spec -> dialog("Edit " + connection.label(),
                                ConnectionsComponent.form(spec, connection, null)))
                        .orElseGet(() -> UiPatch.of().toast(unknownProvider(connection.provider()))))
                .orElseGet(() -> UiPatch.of().toast(gone()));
    }

    /** Attaches a new account. */
    // Under /add/..., not /{provider}: a provider and a connection id are
    // both one path segment, and Spring cannot tell POST /{provider} from
    // POST /{id} — every save was a 500. AdminUiRoutesTest guards this.
    @PostMapping("/add/{provider}")
    public UiPatch add(@AuthenticationPrincipal OidcUser user, @PathVariable("provider") String provider,
                       @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        ConnectionSpec spec = connections.spec(provider).orElse(null);
        ConnectionService service = connections.service().orElse(null);
        if (spec == null || service == null) {
            return UiPatch.of().toast(unknownProvider(provider));
        }
        FormBody body = new FormBody(raw);
        String label = body.str("label");
        try {
            Connection created = service.add(me, provider, label, values(spec, raw), secretFields(spec));
            return refreshed(me, spec).toast(UiToast.success(
                            "\"" + created.label() + "\" is connected. Tools refer to it as "
                                    + created.key() + ".")
                    .title(spec.title() + " connected"));
        } catch (IllegalArgumentException e) {
            return dialog("Connect " + spec.title(), ConnectionsComponent.form(spec, null, e.getMessage()));
        }
    }

    /** Saves an edit; a blank secret keeps the stored one. */
    @PostMapping("/{id}")
    public UiPatch update(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                          @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        Connection stored = found(me, id).orElse(null);
        ConnectionService service = connections.service().orElse(null);
        if (stored == null || service == null) {
            return UiPatch.of().toast(gone());
        }
        ConnectionSpec spec = connections.spec(stored.provider()).orElse(null);
        if (spec == null) {
            return UiPatch.of().toast(unknownProvider(stored.provider()));
        }
        FormBody body = new FormBody(raw);
        try {
            service.update(me, stored.id(), body.str("label"), values(spec, raw), secretFields(spec));
            return refreshed(me, spec).toast(UiToast.success("Saved.").title(stored.label()));
        } catch (IllegalArgumentException e) {
            return dialog("Edit " + stored.label(), ConnectionsComponent.form(spec, stored, e.getMessage()));
        }
    }

    /** Makes one the default: what a call takes when it names none. */
    @PostMapping("/{id}/default")
    public UiPatch makeDefault(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Connection stored = found(me, id).orElse(null);
        if (stored == null) {
            return UiPatch.of().toast(gone());
        }
        connections.service().ifPresent(service -> service.setDefault(me, stored.id()));
        ConnectionSpec spec = connections.spec(stored.provider()).orElse(null);
        UiPatch patch = spec == null ? UiPatch.of() : refreshed(me, spec);
        return patch.toast(UiToast.success("\"" + stored.label() + "\" is now used when a call names none.")
                .title("Default set"));
    }

    /** Detaches it; the next one takes over as default. */
    @DeleteMapping("/{id}")
    public UiPatch remove(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Connection stored = found(me, id).orElse(null);
        if (stored == null) {
            return UiPatch.of().toast(gone());
        }
        connections.service().ifPresent(service -> service.remove(me, stored.id()));
        ConnectionSpec spec = connections.spec(stored.provider()).orElse(null);
        UiPatch patch = spec == null ? UiPatch.of() : refreshed(me, spec);
        return patch.toast(UiToast.success("\"" + stored.label() + "\" is no longer connected.")
                .title("Removed"));
    }

    @PostMapping("/dialog/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(DIALOG_ID));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** The provider's table as it is now, and the dialog closed. */
    private UiPatch refreshed(UserId user, ConnectionSpec spec) {
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.replace(
                        ConnectionsComponent.providerId(spec.provider()) + "-table",
                        ConnectionsComponent.table(new ConnectionsComponent.Card(
                                spec, connections.of(user, spec.provider())))));
    }

    private Optional<Connection> found(UserId user, String id) {
        return connections.service().flatMap(service -> service.find(user, ConnectionId.of(id)));
    }

    /**
     * The form's fields, by the schema's property names — everything else in
     * the body (the label, a CSRF echo) is left out rather than stored.
     */
    private static Map<String, String> values(ConnectionSpec spec, Map<String, Object> raw) {
        Schema schema = spec.form().map(Acquisition.Form::schema).orElse(null);
        if (schema == null || schema.getProperties() == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        schema.getProperties().keySet().forEach(name -> {
            Object value = raw.get(name);
            values.put(name, value == null ? "" : String.valueOf(value).strip());
        });
        return values;
    }

    private static Set<String> secretFields(ConnectionSpec spec) {
        return ToolConnections.secretFields(spec.form().map(Acquisition.Form::schema).orElse(null));
    }

    private static UiPatch dialog(String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    private static UiToast unknownProvider(String provider) {
        return UiToast.error("No installed tool asks for a \"" + provider + "\" connection.")
                .title("Nothing to connect");
    }

    private static UiToast gone() {
        return UiToast.info("That connection is not there any more.").title("Nothing to change");
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(user == null ? "mc_user" : user.getPreferredUsername());
    }
}
