package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.user.domain.User;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The namespaces the signed-in user may work in — the open default one and
 * every one they created or were invited into — each with its people and what
 * they may do there, a form for an admin to invite somebody by e-mail address,
 * and the way to create a new, empty namespace. Data
 * for a new namespace comes from the Registry, or from the user's own
 * hands; nothing is copied.
 */
public final class NamespacesPage {

    public static final String API = "/admin/api/namespaces";
    public static final String NAVIGATE = "/admin/namespaces";
    public static final String CREATE_FORM_ID = "namespace-create-form";

    private final Actor me;
    private final Namespace active;
    private final List<NamespaceDefinition> namespaces;
    private final List<User> users;
    private final Namespace defaultNamespace;
    /**
     * Whether the default namespace takes every signed-in user. It does until
     * somebody is named to shape it ({@code mindconnect.namespace-admins}); from
     * then on it is a namespace like any other and is shown as one.
     */
    private final boolean defaultOpen;

    public NamespacesPage(Actor me, Namespace active, Namespace defaultNamespace,
                          List<NamespaceDefinition> namespaces, List<User> users) {
        this(me, active, defaultNamespace, true, namespaces, users);
    }

    public NamespacesPage(Actor me, Namespace active, Namespace defaultNamespace, boolean defaultOpen,
                          List<NamespaceDefinition> namespaces, List<User> users) {
        this.me = me;
        this.active = active;
        this.defaultNamespace = defaultNamespace;
        this.defaultOpen = defaultOpen;
        this.namespaces = namespaces;
        this.users = users;
    }

    public UiPage render() {
        UiStack root = UiStack.of("namespaces-page").gap(16);
        root.child(UiText.of("namespaces-intro",
                "A namespace holds everything: agents, LLM configs, skills, sessions, files, vector stores, "
                        + "workflows, MCP servers. You work in one at a time — pick it in the header. "
                        + "A new namespace starts empty; fill it from the Registry."));
        UiStack head = UiStack.of("namespaces-actions").direction(UiStack.Direction.HORIZONTAL).gap(8);
        head.child(UiAction.primary("namespace-create", "New namespace").icon("add").dispatch("GET", API + "/new"));
        root.child(head);
        for (NamespaceDefinition ns : namespaces) {
            root.child(namespaceCard(ns));
        }
        return UiPage.of(NAVIGATE, root);
    }

    private UiNode namespaceCard(NamespaceDefinition ns) {
        String id = ns.id().value();
        boolean isDefault = ns.id().equals(defaultNamespace) && defaultOpen;
        String title = ns.label() + (ns.label().equals(id) ? "" : " (" + id + ")")
                + (ns.id().equals(active) ? " — current" : "");
        UiStack card = UiStack.of("namespace-" + id).gap(8);
        if (isDefault) {
            UiTable table = UiTable.of("namespace-" + id + "-members", title).stackOnMobile(true).icon("layers")
                    .column(UiTable.Column.text("user", "Members"));
            table.row(Map.of("id", "everyone", "user", "Every signed-in user — the default namespace is open to all."));
            card.child(table);
            card.child(UiText.of("namespace-" + id + "-environment-note",
                    "The default namespace has no variables of its own: a ${VAR} here takes your own value, "
                            + "else the server's environment. A namespace you create can carry variables for everyone in it."));
            return card;
        }
        boolean iAmAdmin = ns.isAdmin(me);
        UiTable table = UiTable.of("namespace-" + id + "-members", title).stackOnMobile(true).icon("layers")
                .column(UiTable.Column.text("user", "Member"))
                .column(UiTable.Column.text("role", "Role"));
        if (iAmAdmin) {
            // The server decides again on every one of these; the buttons are only
            // here because an admin is the one who would use them.
            table.rowAction(UiAction.secondary("promote", "Make admin").icon("shield")
                            .confirm("Let this person shape the namespace — agents, LLM configs, workflows, "
                                    + "variables, and who else is in it?")
                            .dispatch("POST", API + "/" + id + "/members/{id}/promote"))
                    .rowAction(UiAction.secondary("demote", "Make user").icon("user")
                            .confirm("Take the namespace's content out of this person's hands? "
                                    + "They keep the chat and may run its workflows.")
                            .dispatch("POST", API + "/" + id + "/members/{id}/demote"))
                    .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                            .confirm("Remove this member from the namespace? Their sessions there stay.")
                            .dispatch("DELETE", API + "/" + id + "/members/{id}"));
        }
        if (!ns.id().equals(active)) {
            table.action(UiAction.secondary("switch-" + id, "Switch to").icon("arrow-right")
                    .dispatch("POST", API + "/switch/" + id));
        }
        if (ns.isCreator(me)) {
            table.action(UiAction.danger("delete-" + id, "Delete namespace").icon("delete")
                    .confirm("Delete '" + ns.label() + "' with everything in it — agents, sessions, files, workflows, "
                            + "vector stores, MCP servers? This cannot be undone.")
                    .dispatch("DELETE", API + "/" + id));
        } else {
            table.action(UiAction.secondary("leave-" + id, "Leave").icon("log-out")
                    .confirm("Leave '" + ns.label() + "'? You will need a new invitation to come back.")
                    .dispatch("POST", API + "/" + id + "/leave"));
        }
        for (Email admin : ns.admins()) {
            table.row(memberRow(admin, admin.equals(ns.createdBy()) ? "creator" : "admin"));
        }
        for (Email member : ns.users()) {
            table.row(memberRow(member, "user"));
        }
        card.child(table);
        if (iAmAdmin) {
            card.child(inviteForm(ns));
            card.child(environment(ns));
        }
        return card;
    }

    /** The id of the variables table of {@code ns} — replaced in place after a variable is added or removed. */
    public static String environmentId(Namespace ns) {
        return "namespace-" + ns.value() + "-environment";
    }

    /**
     * The variables of {@code ns}, for its creator: what {@code ${VAR}} placeholders in the
     * namespace's LLM configs resolve to for everyone working here, unless a user set their
     * own. Names only — a value is a secret and is never shown again.
     */
    public static UiTable environment(NamespaceDefinition ns) {
        String id = ns.id().value();
        return environmentTable(environmentId(ns.id()), "Variables of " + ns.label(), ns.environment().keySet(),
                API + "/" + id + "/environment",
                "Remove this variable? A config that refers to it falls back to the server's value, if there is one.");
    }

    /**
     * A table of variables by name — one per owner: a user's own on the profile page, a
     * namespace's here. Values are never shown; {@code apiBase} takes {@code /new} (the add
     * dialog) and {@code /{name}} (a DELETE), {@code confirm} is asked before a removal.
     */
    public static UiTable environmentTable(String tableId, String title, java.util.Collection<String> names,
                                           String apiBase, String confirm) {
        UiTable table = UiTable.of(tableId, title).stackOnMobile(true).icon("key-round")
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("value", "Value"))
                .action(UiAction.secondary(tableId + "-add", "Add variable…").icon("add")
                        .dispatch("GET", apiBase + "/new"))
                .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                        .confirm(confirm)
                        .dispatch("DELETE", apiBase + "/{id}"));
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", name);
            row.put("name", name);
            row.put("value", ai.mindconnect.adminui.ui.AdminPage.MASKED_VALUE);
            table.row(row);
        }
        return table;
    }

    /**
     * The form that adds (or replaces) one variable; {@code error} keeps it open with the
     * reason. {@code target} is where the form posts, {@code close} what the cancel action calls.
     */
    public static UiForm environmentForm(String formId, String error, String target, String close) {
        UiForm form = UiForm.of(formId, null)
                .field(UiField.text("name", "Name", null).asEditable().asRequired()
                        .placeholder("e.g. OPENAI_API_KEY")
                        .hint("As a config refers to it: ${OPENAI_API_KEY}. Letters, digits and '_'."))
                .field(UiField.password("value", "Value", null).asEditable().asRequired()
                        .hint("Stored encrypted and never shown again; adding a name that exists replaces its value."))
                .action(UiAction.primary(formId + "-save", "Save").icon("key-round")
                        .dispatch("POST", target, formId))
                .action(UiAction.secondary(formId + "-cancel", "Cancel")
                        .dispatch("POST", close));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    private static UiForm inviteForm(NamespaceDefinition ns) {
        String id = ns.id().value();
        return inviteForm("namespace-" + id + "-invite", null, id, null, API + "/" + id + "/members");
    }

    /**
     * The form that invites somebody into {@code id} by e-mail address and role;
     * {@code error} keeps it open with the reason. {@code target} is where the form posts.
     */
    public static UiForm inviteForm(String formId, String title, String id, String error, String target) {
        UiForm form = UiForm.of(formId, title)
                .field(UiField.text("email", "Invite", null).asEditable().asRequired()
                        .placeholder("e-mail address, as their account carries it")
                        .hint("The address they sign in with. They do not have to have signed in yet — "
                                + "the membership is waiting when they do."))
                .field(UiField.select("role", "May", NamespaceRole.USER.name(), List.of(
                                UiField.Option.of(NamespaceRole.USER.name(), "Use it — chat, and run its workflows"),
                                UiField.Option.of(NamespaceRole.ADMIN.name(), "Shape it — everything, except deleting it")))
                        .asEditable()
                        .hint("A user sees the chat and the workflows and nothing else; an admin also creates and "
                                + "changes agents, LLM configs, workflows and the namespace's variables."))
                .action(UiAction.primary(formId + "-send", "Invite").icon("user-plus")
                        .dispatch("POST", target, formId));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    private Map<String, Object> memberRow(Email entry, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.value());
        row.put("user", label(entry));
        row.put("role", role);
        return row;
    }

    /**
     * How one listed entry reads: the name of the account behind it when somebody
     * has signed in under it, the bare entry when nobody has yet — an address may
     * be listed before its first sign-in, and saying so is more useful than
     * hiding it.
     */
    private String label(Email entry) {
        String address = entry.value();
        if (me.matches(entry)) return address + " (you)";
        for (User user : users) {
            if (address.equalsIgnoreCase(user.email())) {
                String name = user.label();
                return name.equalsIgnoreCase(address) ? address : name + " (" + address + ")";
            }
        }
        return address + " — not signed in yet";
    }

    /** The "New namespace" dialog body; {@code error} keeps the form open with the reason. */
    public static UiForm createForm(String error) {
        UiForm form = UiForm.of(CREATE_FORM_ID, null)
                .field(UiField.text("id", "Id", null).asEditable().asRequired()
                        .placeholder("e.g. acme, research, my-sandbox")
                        .hint("Lower-case letters, digits, '-' and '_' — it names the directory and the URL prefix, and cannot change."))
                .field(UiField.text("displayName", "Name", null).asEditable()
                        .placeholder("What to show in the switcher; the id when empty"))
                .action(UiAction.primary("create", "Create and switch").icon("add")
                        .dispatch("POST", API, CREATE_FORM_ID))
                .action(UiAction.secondary("cancel", "Cancel")
                        .dispatch("POST", API + "/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }
}
