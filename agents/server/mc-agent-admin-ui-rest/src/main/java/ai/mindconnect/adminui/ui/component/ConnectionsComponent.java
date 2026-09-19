package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.setup.ToolConnections;
import ai.mindconnect.agent.tool.Acquisition;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.workflow.admin.ui.ParamFormFields;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The accounts a user has attached, one card per thing the installed tools
 * ask to be connected.
 *
 * <p>No tool ships a screen. A tool source declares a {@link ConnectionSpec}
 * with a {@link Schema}, and everything here is rendered from that — which is
 * why a commercial module in another repository needs no dependency on the
 * UI, and why a new provider costs no work in the Admin UI at all.
 */
public final class ConnectionsComponent {

    /** The whole tab's content — replaced in place after every change. */
    public static final String ID = "profile-connections";
    public static final String FORM_ID = "connection-form";
    public static final String API = "/admin/api/connections";

    private ConnectionsComponent() { }

    /** One card per provider: what it is, what the user has attached, and how to attach one. */
    public static UiNode render(List<Card> cards, boolean storeAvailable) {
        UiStack stack = UiStack.of(ID).gap(20);
        if (!storeAvailable) {
            stack.child(hint(ID + "-no-store",
                    "This installation keeps no connections, so there is nothing to attach here."));
            return stack;
        }
        if (cards.isEmpty()) {
            stack.child(hint(ID + "-none",
                    "No installed tool asks you to connect an account. Tools that do — a mailbox, "
                            + "a calendar — show up here as soon as they are installed."));
            return stack;
        }
        stack.child(hint(ID + "-note",
                "An account you attach here is yours: it is used when a tool runs for you, in every chat, "
                        + "and nobody else can see it. Passwords are stored encrypted and never shown again."));
        for (Card card : cards) {
            stack.child(card(card));
        }
        return stack;
    }

    /** What one provider's card is made of. */
    public record Card(ConnectionSpec spec, List<Connection> connections) { }

    private static UiNode card(Card card) {
        ConnectionSpec spec = card.spec();
        UiStack stack = UiStack.of(providerId(spec.provider())).gap(8);
        if (spec.description() != null && !spec.description().isBlank()) {
            stack.child(hint(providerId(spec.provider()) + "-about", spec.description()));
        }
        stack.child(table(card));
        return stack;
    }

    /** The table id for one provider — what a change replaces in place. */
    public static String providerId(String provider) {
        return ID + "-" + provider;
    }

    /** One provider's table — replaced in place after every change. */
    public static UiTable table(Card card) {
        ConnectionSpec spec = card.spec();
        List<Connection> connections = card.connections();
        boolean canAddMore = spec.multiple() || connections.isEmpty();

        UiTable table = UiTable.of(providerId(spec.provider()) + "-table", spec.title()).stackOnMobile(true)
                .icon(spec.icon() == null ? "link" : spec.icon())
                .column(UiTable.Column.text("label", "Name"))
                .column(UiTable.Column.text("key", "Referred to as"))
                .column(UiTable.Column.text("status", "Status"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", API + "/{id}/edit"))
                .rowAction(UiAction.secondary("make-default", "Make default").icon("check")
                        .dispatch("POST", API + "/{id}/default"))
                .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                        .confirm("Remove this connection? Tools that used it stop working until you attach "
                                + "another one.")
                        .dispatch("DELETE", API + "/{id}"));

        if (canAddMore) {
            // One button per way of coming by a connection: "Connect with Google"
            // beside "Add manually" on the same card.
            for (Acquisition acquisition : spec.acquisitions()) {
                table.action(addAction(spec, acquisition));
            }
        }
        for (Connection connection : connections) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", connection.id().value());
            row.put("label", connection.label() + (connection.isDefault() ? "  (default)" : ""));
            row.put("key", connection.key());
            row.put("status", status(connection));
            table.row(row);
        }
        return table;
    }

    private static UiAction addAction(ConnectionSpec spec, Acquisition acquisition) {
        String id = providerId(spec.provider()) + "-add-" + acquisition.getClass().getSimpleName().toLowerCase();
        if (acquisition instanceof Acquisition.Form) {
            return UiAction.primary(id, acquisition.label()).icon("add")
                    .dispatch("GET", API + "/" + spec.provider() + "/new");
        }
        // A consent page cannot be fetched into a dialog: this one leaves the
        // SPA, so it is a plain link and not an API call.
        UiAction connect = UiAction.link(id, acquisition.label()).icon("link");
        connect.setHref("/admin/oauth/authorize/" + spec.provider());
        return connect;
    }

    private static String status(Connection connection) {
        if (connection.usable()) return "connected";
        String detail = connection.stateDetail();
        return connection.state().name().toLowerCase() + (detail == null ? "" : " — " + detail);
    }

    // ── the form ────────────────────────────────────────────────────────────

    /**
     * The add/edit form, rendered from the provider's schema. Secrets come back
     * empty on an edit and empty means "leave it" — a password is never shown
     * again, so anything else would wipe it on every rename.
     */
    public static UiForm form(ConnectionSpec spec, Connection editing, String error) {
        Schema schema = spec.form().map(Acquisition.Form::schema).orElse(null);
        String target = editing == null
                ? API + "/" + spec.provider()
                : API + "/" + editing.id().value();

        UiForm form = UiForm.of(FORM_ID, null)
                .field(UiField.text("label", "Name", editing == null ? null : editing.label())
                        .asEditable().asRequired()
                        .placeholder("e.g. Work")
                        .hint(editing == null
                                ? "What you call this account. Tools refer to it by a short form of the name."
                                : "Renaming is safe: tools keep referring to it as \"" + editing.key() + "\"."));
        if (schema != null) {
            ParamFormFields.addTo(form, schema, null, editing == null ? Map.of() : settingsOf(editing));
        }
        form.action(UiAction.primary(FORM_ID + "-save", editing == null ? "Connect" : "Save").icon("link")
                        .dispatch("POST", target, FORM_ID))
                .action(UiAction.secondary(FORM_ID + "-cancel", "Cancel")
                        .dispatch("POST", API + "/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    /** Only the readable half is prefilled; a secret is never sent back to the browser. */
    private static Map<String, String> settingsOf(Connection connection) {
        return connection.settings();
    }

    private static UiText hint(String id, String text) {
        UiText node = UiText.of(id, text);
        node.withCssClass("sui-hint");
        return node;
    }
}
