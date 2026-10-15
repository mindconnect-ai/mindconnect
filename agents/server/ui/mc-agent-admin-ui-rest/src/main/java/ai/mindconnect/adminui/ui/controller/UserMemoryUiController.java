package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The profile's "Memory" tab: what agents remember about the signed-in user
 * across chats — the memory every agent shares and each agent's own — with a
 * view and a delete per entry. Under {@code /admin/api/profile}, so it is
 * open to every member of the namespace, and it only ever touches the
 * caller's own entries: a row naming somebody else is answered like one that
 * does not exist.
 */
@RestController
@RequestMapping(UserMemoryUiController.API)
public class UserMemoryUiController {

    public static final String API = "/admin/api/profile/memory";

    /** Null on a runtime without the memory. */
    private final UserMemoryService memory;
    /** For the agents' names; null leaves their ids. */
    private final AgentDefinitionRepository agents;

    @Autowired
    public UserMemoryUiController(ObjectProvider<UserMemoryService> memory,
                                  ObjectProvider<AgentDefinitionRepository> agents) {
        this(memory.getIfAvailable(), agents.getIfAvailable());
    }

    UserMemoryUiController(UserMemoryService memory, AgentDefinitionRepository agents) {
        this.memory = memory;
        this.agents = agents;
    }

    /** The tab's content for the profile page; empty on a runtime without the memory. */
    public Optional<UiNode> tab(UserId userId) {
        if (memory == null) return Optional.empty();
        return Optional.of(UiStack.of("profile-memory").gap(12)
                .child(UserMemoryComponent.profileHelp())
                .child(table(userId)));
    }

    @GetMapping("/{row}")
    public UiPatch view(@AuthenticationPrincipal OidcUser user, @PathVariable("row") String row) {
        UserId me = userId(user);
        return own(me, row)
                .flatMap(r -> memory.read(r.userId(), r.agentId(), r.name()))
                .map(entry -> dialog(entry.name(), UserMemoryComponent.detail(entry, agentNames())))
                .orElseGet(() -> UiPatch.of().toast(UiToast.error("That memory is gone.").title("Not found")));
    }

    @DeleteMapping("/{row}")
    public UiPatch delete(@AuthenticationPrincipal OidcUser user, @PathVariable("row") String row) {
        UserId me = userId(user);
        boolean deleted = own(me, row).map(r -> memory.delete(r.userId(), r.agentId(), r.name())).orElse(false);
        UiPatch patch = UiPatch.of()
                .patch(UiPatch.Operation.replace(UserMemoryComponent.PROFILE_TABLE_ID, table(me)));
        return deleted
                ? patch.toast(UiToast.success("The agents will no longer know it.").title("Memory deleted"))
                : patch.toast(UiToast.error("That memory is gone.").title("Not found"));
    }

    private UiNode table(UserId userId) {
        List<MemoryEntry> entries = memory == null ? List.of() : memory.list(userId);
        return UserMemoryComponent.table(UserMemoryComponent.PROFILE_TABLE_ID, API, entries, agentNames(), false);
    }

    /** The row, if it is one of the caller's own entries. */
    private Optional<Row> own(UserId me, String row) {
        if (memory == null) return Optional.empty();
        return Row.parse(row).filter(r -> r.userId().equals(me));
    }

    Function<AgentId, String> agentNames() {
        return agentNames(agents);
    }

    static Function<AgentId, String> agentNames(AgentDefinitionRepository agents) {
        if (agents == null) return id -> null;
        return id -> {
            try {
                return agents.findById(id).map(AgentDefinition::name).orElse(null);
            } catch (RuntimeException e) {
                return null;   // a name is a nicety; the id stands in
            }
        };
    }

    static UiPatch dialog(String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(UserMemoryComponent.DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(UserMemoryComponent.DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(SessionOwnership.userIdOf(user));
    }
}
