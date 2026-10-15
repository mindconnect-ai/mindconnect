package ai.mindconnect.adminui.ui.controller;

import java.util.Set;
import ai.mindconnect.agent.tool.ToolBundles;
import ai.mindconnect.adminui.setup.UserTools;
import ai.mindconnect.adminui.ui.component.UserToolsComponent;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.service.UserToolService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The tools the signed-in user keeps in their own account: adding one,
 * changing it, switching it off, taking it away.
 *
 * <p>Adding is two steps on purpose — which tool, then how — because the
 * second question depends on the first: the accounts to choose from are the
 * ones that fit <em>that</em> tool's provider.
 *
 * <p>Everything acts on the caller's own bindings; an id that is not theirs is
 * answered like one that does not exist.
 */
@RestController
@RequestMapping(UserToolsComponent.API)
public class UserToolUiController {

    static final String DIALOG_ID = "user-tool-dialog";

    private final UserTools userTools;

    public UserToolUiController(UserTools userTools) {
        this.userTools = Objects.requireNonNull(userTools, "userTools");
    }

    /** Step one: which tool. */
    @GetMapping("/new")
    public UiPatch pick() {
        ToolBundles bundles = userTools.bundles();
        if (bundles.isEmpty()) {
            return UiPatch.of().toast(UiToast.info("This installation offers no tools to add.")
                    .title("Nothing to add"));
        }
        return dialog("Add tools", UserToolsComponent.pickForm(bundles, null));
    }

    /**
     * Step two: how. One single tool gets the full form, name of its own
     * included; anything else — a set, or several picks — gets the account
     * and the approval switch, and lands as one row per pick.
     */
    @PostMapping("/new")
    public UiPatch configure(@AuthenticationPrincipal OidcUser user, @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        ToolBundles bundles = userTools.bundles();
        List<String> picks = new FormBody(raw).strList("picks").stream()
                .filter(key -> bundles.find(key).isPresent()).toList();
        if (picks.isEmpty()) {
            return dialog("Add tools", UserToolsComponent.pickForm(bundles, "Pick something from the list."));
        }
        if (picks.size() == 1 && !ToolBundles.isSet(picks.get(0))) {
            String toolName = bundles.expand(picks).get(0);
            return dialog("Add " + toolName, UserToolsComponent.form(toolName,
                    userTools.connectionSpecOf(toolName), userTools.connectionsFor(me, toolName), null, null));
        }
        List<String> names = picks.stream().map(this::nameOf).toList();
        Optional<ConnectionSpec> shared = sharedSpec(names);
        return dialog("Add to your account", UserToolsComponent.bundleForm(names, bundles, shared,
                shared.isPresent() ? userTools.connectionsFor(me, names.get(0)) : List.of(), null));
    }

    /** Several at once, one row each; a name already in the account is left as it is. */
    @PostMapping("/add-many")
    public UiPatch addMany(@AuthenticationPrincipal OidcUser user, @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        UserToolService service = userTools.service().orElse(null);
        FormBody body = new FormBody(raw);
        String picked = body.str("picks");
        List<String> names = picked == null ? List.of()
                : java.util.Arrays.stream(picked.split(",")).map(String::strip)
                        .filter(userTools::isKnown).toList();
        if (service == null || names.isEmpty()) {
            return UiPatch.of().toast(UiToast.error("Nothing to add.").title("Nothing added"));
        }
        Set<String> present = new java.util.HashSet<>();
        userTools.of(me).forEach(tool -> present.add(tool.effectiveName()));
        int added = 0;
        for (String name : names) {
            if (!present.add(name)) continue;
            service.add(me, null, name, null, null, params(body), body.bool("needsApproval"), members(body, name));
            added++;
        }
        int skipped = names.size() - added;
        return refreshed(me).toast(UiToast.success(added + (added == 1 ? " entry is" : " entries are")
                        + " in your chats from the next message on."
                        + (skipped == 0 ? "" : " " + skipped + " you already had."))
                .title("Added"));
    }

    /**
     * The members of set {@code key} as the two checkbox groups left them: a
     * tool not ticked under "Tools in it" is out, one ticked under "Ask me
     * before" asks. Tools of other sets in the same form are not this set's.
     */
    private Map<String, UserTool.Member> members(FormBody body, String key) {
        if (!ToolBundles.isSet(key)) return Map.of();
        Set<String> in = new java.util.HashSet<>(body.strList("members"));
        Set<String> asks = new java.util.HashSet<>(body.strList("asks"));
        Map<String, UserTool.Member> members = new java.util.LinkedHashMap<>();
        for (String tool : userTools.membersOf(key)) {
            boolean enabled = in.isEmpty() || in.contains(tool);   // no group rendered at all: everything in
            Boolean ask = asks.contains(tool) ? Boolean.TRUE : null;
            if (!enabled || ask != null) members.put(tool, new UserTool.Member(enabled, ask));
        }
        return members;
    }

    private String rowName(UserTool tool) {
        return ToolBundles.isSet(tool.toolName()) ? userTools.bundles().label(tool.toolName()) : tool.effectiveName();
    }

    /** What is stored for a pick: a set under its key, a single tool under its name. */
    private String nameOf(String pick) {
        return ToolBundles.isSet(pick) ? pick : userTools.bundles().expand(List.of(pick)).get(0);
    }

    /** The one connection kind all of them run on — or empty, when they differ or need none. */
    private Optional<ConnectionSpec> sharedSpec(List<String> names) {
        Set<String> providers = new java.util.HashSet<>();
        Optional<ConnectionSpec> first = Optional.empty();
        for (String name : names) {
            Optional<ConnectionSpec> spec = userTools.connectionSpecOf(name);
            if (spec.isEmpty()) return Optional.empty();
            providers.add(spec.get().provider());
            if (first.isEmpty()) first = spec;
        }
        return providers.size() == 1 ? first : Optional.empty();
    }

    /**
     * Adds it to the user's account.
     *
     * <p>Under {@code /add/...}, not {@code /{toolName}}: a tool name and a
     * user-tool id are both one path segment, and Spring cannot tell
     * {@code POST /{toolName}} from {@code POST /{id}} — every add was a 500.
     */
    @PostMapping("/add/{toolName}")
    public UiPatch add(@AuthenticationPrincipal OidcUser user, @PathVariable("toolName") String toolName,
                       @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        UserToolService service = userTools.service().orElse(null);
        if (service == null || !userTools.isKnown(toolName)) {
            return UiPatch.of().toast(UiToast.error("No such tool: " + toolName).title("Nothing added"));
        }
        FormBody body = new FormBody(raw);
        try {
            UserTool created = service.add(me, null, toolName, body.str("alias"), body.str("description"),
                    params(body), body.bool("needsApproval"));
            return refreshed(me).toast(UiToast.success(
                            "\"" + created.effectiveName() + "\" is in your chats from the next message on.")
                    .title("Tool added"));
        } catch (IllegalArgumentException e) {
            return dialog("Add " + toolName, UserToolsComponent.form(toolName,
                    userTools.connectionSpecOf(toolName), userTools.connectionsFor(me, toolName),
                    null, e.getMessage()));
        }
    }

    /** Opens the form for one the user already has. */
    @GetMapping("/{id}/edit")
    public UiPatch edit(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        return userTools.find(me, UserToolId.of(id))
                .map(tool -> dialog("Edit " + rowName(tool), UserToolsComponent.form(
                        tool.toolName(), userTools.connectionSpecOf(tool.toolName()),
                        userTools.connectionsFor(me, tool.toolName()), tool, null, userTools.bundles())))
                .orElseGet(() -> UiPatch.of().toast(gone()));
    }

    /** Saves the edit. */
    @PostMapping("/{id}")
    public UiPatch update(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id,
                          @RequestBody Map<String, Object> raw) {
        UserId me = userId(user);
        UserTool stored = userTools.find(me, UserToolId.of(id)).orElse(null);
        UserToolService service = userTools.service().orElse(null);
        if (stored == null || service == null) {
            return UiPatch.of().toast(gone());
        }
        FormBody body = new FormBody(raw);
        try {
            service.update(me, stored.id(), body.str("alias"), body.str("description"),
                    params(body), stored.enabled(), body.bool("needsApproval"));
            if (ToolBundles.isSet(stored.toolName())) {
                service.updateMembers(me, stored.id(), members(body, stored.toolName()));
            }
            return refreshed(me).toast(UiToast.success("Saved.").title(stored.effectiveName()));
        } catch (IllegalArgumentException e) {
            return dialog("Edit " + stored.effectiveName(), UserToolsComponent.form(
                    stored.toolName(), userTools.connectionSpecOf(stored.toolName()),
                    userTools.connectionsFor(me, stored.toolName()), stored, e.getMessage()));
        }
    }

    /** Switches it off, or back on. */
    @PostMapping("/{id}/toggle")
    public UiPatch toggle(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        UserTool stored = userTools.find(me, UserToolId.of(id)).orElse(null);
        if (stored == null) {
            return UiPatch.of().toast(gone());
        }
        boolean nowOn = !stored.offered();
        userTools.service().ifPresent(service -> service.setEnabled(me, stored.id(), nowOn));
        return refreshed(me).toast(UiToast.success("\"" + stored.effectiveName() + "\" is now "
                + (nowOn ? "offered in your chats." : "out of your chats.")).title("Changed"));
    }

    /** Takes it out of the account. */
    @DeleteMapping("/{id}")
    public UiPatch remove(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        UserTool stored = userTools.find(me, UserToolId.of(id)).orElse(null);
        if (stored == null) {
            return UiPatch.of().toast(gone());
        }
        userTools.service().ifPresent(service -> service.remove(me, stored.id()));
        return refreshed(me).toast(UiToast.success("\"" + stored.effectiveName() + "\" is gone from your chats.")
                .title("Removed"));
    }

    @PostMapping("/dialog/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(DIALOG_ID));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * The account the form chose, as a pinned parameter. Empty means "follow my
     * default", which is stored as no pin at all rather than as the default's
     * key — so changing the default later moves this tool with it.
     */
    private static Map<String, Object> params(FormBody body) {
        String account = body.str("account");
        return account == null || account.isBlank() ? Map.of() : Map.of("account", account.strip());
    }

    private UiPatch refreshed(UserId user) {
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.replace(UserToolsComponent.TABLE_ID,
                        UserToolsComponent.table(rows(user), !userTools.catalogue().isEmpty())));
    }

    /** One row per binding, with the spec and the connection it points at. */
    public List<UserToolsComponent.Row> rows(UserId user) {
        return userTools.of(user).stream().map(tool -> {
            Optional<ConnectionSpec> spec = userTools.connectionSpecOf(tool.toolName());
            List<Connection> available = userTools.connectionsFor(user, tool.toolName());
            String pinned = UserToolsComponent.pinnedAccount(tool);
            Optional<Connection> account = pinned == null
                    ? available.stream().filter(Connection::isDefault).findFirst()
                    : available.stream().filter(c -> c.key().equals(pinned)).findFirst();
            if (ToolBundles.isSet(tool.toolName())) {
                List<String> members = userTools.membersOf(tool.toolName());
                return new UserToolsComponent.Row(tool, spec, account, userTools.bundles().label(tool.toolName()),
                        members.size() + " tools");
            }
            return new UserToolsComponent.Row(tool, spec, account);
        }).toList();
    }

    private static UiPatch dialog(String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    private static UiToast gone() {
        return UiToast.info("That tool is not in your account any more.").title("Nothing to change");
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(user == null ? "mc_user" : user.getPreferredUsername());
    }
}
