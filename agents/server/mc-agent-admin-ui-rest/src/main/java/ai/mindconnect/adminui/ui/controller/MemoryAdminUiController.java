package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryReach;
import ai.mindconnect.agent.runtime.usermemory.MemoryReadTool;
import ai.mindconnect.agent.runtime.usermemory.MemoryWriteTool;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The admin's view of the memory: every entry of every user in the current
 * namespace — the memory each user's agents share and each agent's own —
 * filterable by user and by memory, with a view and a delete per entry; and
 * the "Memory" tab of an agent's page, with what that agent keeps about its
 * users. For admins of the namespace only: neither {@code /admin/memories}
 * nor an agent's page is among the paths the access guard opens to plain
 * members.
 */
@RestController
@RequestMapping(MemoryAdminUiController.API)
public class MemoryAdminUiController {

    public static final String API = "/admin/api/memories";
    static final String PAGE = "/admin/memories";
    static final String SEARCH_FORM_ID = "memory-search";

    /** The memory filter's value for the memory every agent of a user shares. */
    static final String SHARED = "shared";

    /** Null on a runtime without the memory. */
    private final UserMemoryService memory;
    private final AgentDefinitionRepository agents;

    @Autowired
    public MemoryAdminUiController(ObjectProvider<UserMemoryService> memory,
                                   ObjectProvider<AgentDefinitionRepository> agents) {
        this(memory.getIfAvailable(), agents.getIfAvailable());
    }

    MemoryAdminUiController(UserMemoryService memory, AgentDefinitionRepository agents) {
        this.memory = memory;
        this.agents = agents;
    }

    @GetMapping
    public UiPage list(@RequestParam(value = "user", required = false) String user,
                       @RequestParam(value = "agent", required = false) String agent) {
        return page(user, agent);
    }

    /** The filter form: a change re-renders the page for the users and the memory it names. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw) {
        return page(string(raw, "q"), string(raw, "agent"));
    }

    @GetMapping("/{row}")
    public UiPatch view(@PathVariable("row") String row) {
        if (memory == null) return notFound();
        return Row.parse(row)
                .flatMap(r -> memory.read(r.userId(), r.agentId(), r.name()))
                .map(entry -> UserMemoryUiController.dialog(entry.name(),
                        UserMemoryComponent.detail(entry, agentNames())))
                .orElseGet(MemoryAdminUiController::notFound);
    }

    /**
     * Deletes the entry and redraws the table it was deleted from: an
     * agent's tab when {@code agent} names the agent, else the admin page's.
     */
    @DeleteMapping("/{row}")
    public UiPatch delete(@PathVariable("row") String row,
                          @RequestParam(value = "agent", required = false) String agent) {
        if (memory == null) return notFound();
        boolean deleted = Row.parse(row).map(r -> memory.delete(r.userId(), r.agentId(), r.name())).orElse(false);
        UiNode table = agent == null || agent.isBlank()
                ? adminTable(null, null)
                : agentTable(AgentId.of(agent));
        UiPatch patch = UiPatch.of().patch(UiPatch.Operation.replace(
                agent == null || agent.isBlank() ? UserMemoryComponent.ADMIN_TABLE_ID : UserMemoryComponent.AGENT_TABLE_ID,
                table));
        return deleted
                ? patch.toast(UiToast.success("The user's agents will no longer know it.").title("Memory deleted"))
                : patch.toast(UiToast.error("That memory is gone.").title("Not found"));
    }

    /**
     * The "Memory" tab of an agent's page: what the agent keeps about its
     * users in its own memory. Shown when its memory tools reach an own
     * memory ({@code scope} {@code agent} or {@code both}) — or when entries
     * of it are left from a time they did, so they can still be seen and
     * deleted. The shared memory is not here: it belongs to no agent.
     */
    public Optional<UiNode> agentTab(AgentDefinition agent) {
        if (memory == null || agent == null) return Optional.empty();
        List<MemoryEntry> entries = ownEntries(agent.id());
        boolean keepsOwn = reach(agent).map(MemoryReach::includesAgent).orElse(false);
        if (!keepsOwn && entries.isEmpty()) return Optional.empty();
        String help = keepsOwn
                ? "What this agent keeps about each of its users in a memory of its own, across chats. No "
                  + "other agent sees it. The memory the users' agents share is on Data → Memory."
                : "This agent's memory tools no longer keep a memory of their own (scope), but these entries "
                  + "are left from before. The agent does not see them now.";
        return Optional.of(UiStack.of("agent-memory").gap(12)
                .child(UiText.of("agent-memory-help", help))
                .child(agentTable(agent.id(), entries)));
    }

    private UiPage page(String user, String agent) {
        UiStack body = UiStack.of("admin-memory").gap(16)
                .child(UiText.of("admin-memory-help",
                        "What agents have remembered about the users of this namespace across chats. "
                        + "\"Shared by all agents\" is a user's own memory, which every agent with the "
                        + "memory tools sees; an agent's memory only that agent sees. Users see and delete "
                        + "their own entries on their profile; an agent's page shows what it keeps."))
                .child(searchForm(user, agent));
        if (memory == null) {
            body.child(UiText.of("admin-memory-off", "This runtime keeps no memory."));
        } else {
            body.child(adminTable(user, agent));
        }
        return UiPage.of(PAGE, body);
    }

    private UiNode adminTable(String user, String agent) {
        List<MemoryEntry> entries = memory == null ? List.of() : memory.listAll();
        if (user != null && !user.isBlank()) {
            String needle = user.strip().toLowerCase(Locale.ROOT);
            entries = entries.stream()
                    .filter(e -> e.userId().value().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        if (agent != null && !agent.isBlank()) {
            entries = entries.stream()
                    .filter(e -> SHARED.equals(agent) ? e.shared() : !e.shared() && e.agentId().value().equals(agent))
                    .toList();
        }
        return UserMemoryComponent.table(UserMemoryComponent.ADMIN_TABLE_ID, API, entries, agentNames(), true);
    }

    private UiNode agentTable(AgentId agentId) {
        return agentTable(agentId, ownEntries(agentId));
    }

    private UiNode agentTable(AgentId agentId, List<MemoryEntry> entries) {
        return UserMemoryComponent.table(UserMemoryComponent.AGENT_TABLE_ID, API, entries, agentNames(), true,
                "?agent=" + agentId.value());
    }

    private List<MemoryEntry> ownEntries(AgentId agentId) {
        return memory.listAll().stream().filter(e -> agentId.equals(e.agentId())).toList();
    }

    /** The reach of the agent's memory tools — the write tool's binding decides, as in the prompt. */
    private static Optional<MemoryReach> reach(AgentDefinition agent) {
        if (agent.tools() == null) return Optional.empty();
        Optional<AgentTool> write = binding(agent, MemoryWriteTool.NAME);
        return write.or(() -> binding(agent, MemoryReadTool.NAME)).map(MemoryReach::of);
    }

    private static Optional<AgentTool> binding(AgentDefinition agent, String name) {
        return agent.tools().stream().filter(t -> t.enabled() && name.equals(t.name())).findFirst();
    }

    /** The memory filter: all, the shared memory, or one agent's — every agent that keeps entries. */
    private UiForm searchForm(String user, String agent) {
        UiForm form = UiForm.of(SEARCH_FORM_ID, null);
        form.field(UiField.text("q", "", user == null ? "" : user)
                .asEditable()
                .icon("search")
                .placeholder("Filter by user…")
                .onChange(trigger(on(MemoryAdminUiController.class).search(null), SEARCH_FORM_ID)));
        Map<String, String> options = new LinkedHashMap<>();
        options.put("", "All memories");
        options.put(SHARED, "Shared by all agents");
        Function<AgentId, String> names = agentNames();
        if (memory != null) {
            List<AgentId> owners = new ArrayList<>();
            memory.listAll().stream().filter(e -> !e.shared()).map(MemoryEntry::agentId)
                    .distinct().forEach(owners::add);
            for (AgentId owner : owners) {
                String name = names.apply(owner);
                options.put(owner.value(), "Agent: " + (name == null ? owner.value() : name));
            }
        }
        form.field(UiField.select("agent", "", agent == null ? "" : agent, options.entrySet().stream()
                        .map(o -> UiField.Option.of(o.getKey(), o.getValue())).toList())
                .asEditable()
                .onChange(trigger(on(MemoryAdminUiController.class).search(null), SEARCH_FORM_ID)));
        return form;
    }

    private Function<AgentId, String> agentNames() {
        return UserMemoryUiController.agentNames(agents);
    }

    private static String string(Map<String, Object> raw, String key) {
        Object value = raw == null ? null : raw.get(key);
        return value == null ? null : value.toString();
    }

    private static UiPatch notFound() {
        return UiPatch.of().toast(UiToast.error("That memory is gone.").title("Not found"));
    }
}
