package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
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
 * every one they created or were invited into — each with its members, a form
 * for the creator to invite another user by name, and the way to create a new,
 * empty namespace. Data
 * for a new namespace comes from the Registry, or from the user's own
 * hands; nothing is copied.
 */
public final class NamespacesPage {

    public static final String API = "/admin/api/namespaces";
    public static final String NAVIGATE = "/admin/namespaces";
    public static final String CREATE_FORM_ID = "namespace-create-form";

    private final UserId me;
    private final Namespace active;
    private final List<NamespaceDefinition> namespaces;
    private final List<User> users;
    private final Namespace defaultNamespace;

    public NamespacesPage(UserId me, Namespace active, Namespace defaultNamespace,
                          List<NamespaceDefinition> namespaces, List<User> users) {
        this.me = me;
        this.active = active;
        this.defaultNamespace = defaultNamespace;
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
        boolean isDefault = ns.id().equals(defaultNamespace);
        String title = ns.label() + (ns.label().equals(id) ? "" : " (" + id + ")")
                + (ns.id().equals(active) ? " — current" : "");
        UiStack card = UiStack.of("namespace-" + id).gap(8);
        if (isDefault) {
            UiTable table = UiTable.of("namespace-" + id + "-members", title).icon("layers")
                    .column(UiTable.Column.text("user", "Members"));
            table.row(Map.of("id", "everyone", "user", "Every signed-in user — the default namespace is open to all."));
            card.child(table);
            return card;
        }
        UiTable table = UiTable.of("namespace-" + id + "-members", title).icon("layers")
                .column(UiTable.Column.text("user", "Member"))
                .column(UiTable.Column.text("role", "Role"))
                .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                        .confirm("Remove this member from the namespace? Their sessions there stay.")
                        .dispatch("DELETE", API + "/" + id + "/members/{id}"));
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
        for (UserId member : ns.members()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", member.value());
            row.put("user", label(member));
            row.put("role", ns.isCreator(member) ? "creator" : "member");
            table.row(row);
        }
        card.child(table);
        if (ns.isCreator(me)) {
            card.child(inviteForm(ns));
        }
        return card;
    }

    private static UiForm inviteForm(NamespaceDefinition ns) {
        String id = ns.id().value();
        return inviteForm("namespace-" + id + "-invite", null, id, null, API + "/" + id + "/members");
    }

    /**
     * The form that invites a user into {@code id} by their user name; {@code error} keeps it
     * open with the reason. {@code target} is where the form posts.
     */
    public static UiForm inviteForm(String formId, String title, String id, String error, String target) {
        UiForm form = UiForm.of(formId, title)
                .field(UiField.text("user", "Invite", null).asEditable().asRequired()
                        .placeholder("user name, as they sign in")
                        .hint("The user name at the identity provider. The person has to have signed in here once; "
                                + "members see everything in the namespace."))
                .action(UiAction.primary(formId + "-send", "Invite").icon("user-plus")
                        .dispatch("POST", target, formId));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    private String label(UserId id) {
        for (User user : users) {
            if (user.id().equals(id)) {
                String name = user.label();
                return name.equals(id.value()) ? name : name + " (" + id.value() + ")";
            }
        }
        return id.equals(me) ? id.value() + " (you)" : id.value();
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
