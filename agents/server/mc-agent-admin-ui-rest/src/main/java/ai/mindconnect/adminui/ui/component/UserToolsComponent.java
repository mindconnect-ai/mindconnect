package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.user.domain.UserTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools a user keeps in their own account: what they added, and the way to
 * add another.
 *
 * <p>The point of the screen is the second column of the table. A tool can
 * appear twice — once per account — and the table says which of the user's
 * mailboxes each row runs on. That is the thing an operator could only do by
 * hand in a shared agent definition, and this is where a person does it for
 * themselves.
 */
public final class UserToolsComponent {

    /** The whole tab — replaced in place after every change. */
    public static final String ID = "profile-user-tools";
    public static final String TABLE_ID = ID + "-table";
    public static final String PICK_FORM_ID = "user-tool-pick-form";
    public static final String FORM_ID = "user-tool-form";
    public static final String API = "/admin/api/user-tools";

    private UserToolsComponent() { }

    public static UiNode render(List<Row> rows, Map<String, java.util.Set<String>> catalogue,
                                boolean storeAvailable) {
        UiStack stack = UiStack.of(ID).gap(12);
        if (!storeAvailable) {
            stack.child(hint(ID + "-no-store",
                    "This installation keeps no tools per user, so there is nothing to add here."));
            return stack;
        }
        stack.child(hint(ID + "-note",
                "A tool you add here is offered in every chat of yours, on top of what the agent itself "
                        + "brings — and only in your chats. Add the same tool twice, pointed at two of your "
                        + "accounts, and you get one entry per account. Sub-agents keep the tools their own "
                        + "definition gives them."));
        stack.child(table(rows, !catalogue.isEmpty()));
        return stack;
    }

    /** One of the user's bindings, with what is needed to describe it. */
    public record Row(UserTool tool, Optional<ConnectionSpec> spec, Optional<Connection> account) { }

    public static UiTable table(List<Row> rows, boolean canAdd) {
        UiTable table = UiTable.of(TABLE_ID, "My tools").stackOnMobile(true).icon("settings")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("tool", "Tool"))
                .column(UiTable.Column.text("account", "Account"))
                .column(UiTable.Column.text("status", "Status"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", API + "/{id}/edit"))
                .rowAction(UiAction.secondary("toggle", "On / off").icon("check")
                        .dispatch("POST", API + "/{id}/toggle"))
                .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                        .confirm("Remove this tool from your account? Your chats stop offering it.")
                        .dispatch("DELETE", API + "/{id}"));
        if (canAdd) {
            table.action(UiAction.primary(ID + "-add", "Add tool…").icon("add")
                    .dispatch("GET", API + "/new"));
        }
        for (Row row : rows) {
            UserTool tool = row.tool();
            Map<String, Object> cells = new LinkedHashMap<>();
            cells.put("id", tool.id().value());
            cells.put("name", tool.effectiveName());
            cells.put("tool", tool.toolName());
            cells.put("account", account(row));
            cells.put("status", status(tool));
            table.row(cells);
        }
        return table;
    }

    private static String account(Row row) {
        if (row.spec().isEmpty()) return "—";
        String pinned = pinnedAccount(row.tool());
        if (pinned == null) {
            return row.account().map(c -> c.label() + " (your default)").orElse("none connected");
        }
        return row.account().map(Connection::label).orElse(pinned + " — not connected any more");
    }

    /** The account this binding fixes, or null when it follows the user's default. */
    public static String pinnedAccount(UserTool tool) {
        return tool.params().get("account") instanceof String key && !key.isBlank() ? key : null;
    }

    private static String status(UserTool tool) {
        List<String> notes = new ArrayList<>();
        notes.add(tool.offered() ? "on" : "off");
        if (tool.tightensApproval()) notes.add("asks first");
        return String.join(" · ", notes);
    }

    // ── step one: which tool ────────────────────────────────────────────────

    /** The first dialog: pick a tool. Two steps, because the second depends on the answer. */
    public static UiForm pickForm(Map<String, java.util.Set<String>> catalogue, String error) {
        List<UiField.Option> options = new ArrayList<>();
        catalogue.forEach((group, names) ->
                names.forEach(name -> options.add(UiField.Option.of(name, name + "  (" + group + ")"))));
        UiForm form = UiForm.of(PICK_FORM_ID, null)
                .field(UiField.select("toolName", "Tool", null, options).asEditable().asRequired()
                        .hint("What the model will be able to call. Which of your accounts it uses comes next."))
                .action(UiAction.primary(PICK_FORM_ID + "-next", "Continue").icon("arrow-right")
                        .dispatch("POST", API + "/new", PICK_FORM_ID))
                .action(UiAction.secondary(PICK_FORM_ID + "-cancel", "Cancel")
                        .dispatch("POST", API + "/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    // ── step two: how ───────────────────────────────────────────────────────

    /**
     * The second dialog: what to call it, which account it runs on, whether it
     * should ask first. The account select lists exactly this user's
     * connections for the tool's provider — it cannot name one they do not have.
     */
    public static UiForm form(String toolName, Optional<ConnectionSpec> spec, List<Connection> connections,
                              UserTool editing, String error) {
        boolean editingExisting = editing != null;
        String target = editingExisting ? API + "/" + editing.id().value() : API + "/add/" + toolName;

        UiForm form = UiForm.of(FORM_ID, null)
                .field(UiField.text("alias", "Name in your chats",
                                editingExisting ? editing.alias() : null)
                        .asEditable()
                        .placeholder(toolName + "_work")
                        .hint("Leave it empty to use the tool's own name, which changes the agent's own "
                                + "entry instead of adding a second one. Give it a name to have both."))
                .field(UiField.textarea("description", "What to tell the model",
                                editingExisting ? editing.description() : null)
                        .asEditable()
                        .hint("Optional. \"Reads my work mailbox\" helps the model pick the right one."));

        if (spec.isPresent()) {
            form.field(accountField(spec.get(), connections, editing));
        }
        form.field(UiField.bool("needsApproval", "Ask me before every call",
                        editingExisting && editing.tightensApproval())
                .asEditable()
                .hint("Only ever adds a question. It cannot take away one your agent already asks."));

        form.action(UiAction.primary(FORM_ID + "-save", editingExisting ? "Save" : "Add").icon("add")
                        .dispatch("POST", target, FORM_ID))
                .action(UiAction.secondary(FORM_ID + "-cancel", "Cancel")
                        .dispatch("POST", API + "/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    private static UiField accountField(ConnectionSpec spec, List<Connection> connections, UserTool editing) {
        List<UiField.Option> options = new ArrayList<>();
        options.add(UiField.Option.of("", "Whichever is my default"));
        connections.forEach(c -> options.add(UiField.Option.of(c.key(),
                c.label() + (c.isDefault() ? "  (default)" : ""))));
        String selected = editing == null ? "" : Optional.ofNullable(pinnedAccount(editing)).orElse("");
        UiField field = UiField.select("account", spec.title(), selected, options).asEditable();
        return connections.isEmpty()
                ? field.hint("You have not connected a " + spec.title() + " yet — do that on the "
                        + "Connections tab first, then come back.")
                : field.hint("Fixing one account here is what lets the same tool appear twice, once per "
                        + "account. The model cannot change it.");
    }

    private static UiText hint(String id, String text) {
        UiText node = UiText.of(id, text);
        node.withCssClass("sui-hint");
        return node;
    }
}
