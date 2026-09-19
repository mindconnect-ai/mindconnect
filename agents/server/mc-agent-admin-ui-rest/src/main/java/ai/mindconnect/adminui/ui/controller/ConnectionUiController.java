package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.setup.ToolConnections;
import ai.mindconnect.adminui.ui.component.ConnectionsComponent;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Acquisition;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ConnectionTest;
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
import java.util.List;
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
        Map<String, String> values = values(spec, raw);
        List<String> missing = missing(spec, values, Set.of());
        if (!missing.isEmpty()) {
            return dialog("Connect " + spec.title(), ConnectionsComponent.form(spec, null, needed(missing)));
        }
        try {
            Connection created = service.add(me, provider, label, values, secretFields(spec));
            // Tried out right away, while the person who typed the password
            // is still looking: a wrong one is corrected now or never.
            return refreshed(me, spec).toast(tested(created,
                    "\"" + created.label() + "\" is connected. Tools refer to it as " + created.key() + ".",
                    spec.title() + " connected"));
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
        Map<String, String> values = values(spec, raw);
        // A blank secret means "keep the stored one", so it is not missing here.
        List<String> missing = missing(spec, values, secretFields(spec));
        if (!missing.isEmpty()) {
            return dialog("Edit " + stored.label(), ConnectionsComponent.form(spec, stored, needed(missing)));
        }
        try {
            service.update(me, stored.id(), body.str("label"), values, secretFields(spec));
            Connection saved = found(me, id).orElse(stored);
            return refreshed(me, spec).toast(tested(saved, "Saved.", saved.label()));
        } catch (IllegalArgumentException e) {
            return dialog("Edit " + stored.label(), ConnectionsComponent.form(spec, stored, e.getMessage()));
        }
    }

    /** Tries it out, and writes the verdict into the Status column. */
    @PostMapping("/{id}/test")
    public UiPatch test(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        Connection stored = found(me, id).orElse(null);
        if (stored == null) {
            return UiPatch.of().toast(gone());
        }
        ConnectionTest result = connections.test(stored).orElse(null);
        ConnectionSpec spec = connections.spec(stored.provider()).orElse(null);
        UiPatch patch = spec == null ? UiPatch.of() : refreshed(me, spec);
        if (result == null) {
            return patch.toast(UiToast.info("The " + (spec == null ? stored.provider() : spec.title())
                    + " tools offer no test. Use one of them and see.").title(stored.label()));
        }
        return patch.toast(verdict(stored, result));
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
                                spec, connections.of(user, spec.provider()),
                                connections.tester(spec.provider()).isPresent()))));
    }

    /**
     * The toast after a save: the plain one when the provider offers no test,
     * the verdict when it does — a save that fails its test is worth more
     * than "Saved."
     */
    private UiToast tested(Connection saved, String message, String title) {
        return connections.test(saved)
                .map(result -> verdict(saved, result))
                .orElseGet(() -> UiToast.success(message).title(title));
    }

    private static UiToast verdict(Connection connection, ConnectionTest result) {
        return result.ok()
                ? UiToast.success(result.message()).title("\"" + connection.label() + "\" works")
                : UiToast.error(result.message()).title("\"" + connection.label() + "\" does not work yet").sticky();
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

    /**
     * The schema's required fields the form left blank. The store does not
     * know the schema, so this is the one place the requirement is enforced —
     * without it an empty body once produced a mailbox with no host.
     */
    private static List<String> missing(ConnectionSpec spec, Map<String, String> values, Set<String> allowedBlank) {
        Schema schema = spec.form().map(Acquisition.Form::schema).orElse(null);
        if (schema == null || schema.getRequired() == null) return List.of();
        return schema.getRequired().stream()
                .filter(name -> !allowedBlank.contains(name))
                .filter(name -> values.getOrDefault(name, "").isBlank())
                .toList();
    }

    private static String needed(List<String> missing) {
        return (missing.size() == 1 ? "\"" + missing.get(0) + "\" is" : String.join(", ", missing) + " are")
                + " required.";
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
