package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The admin's view of the memory: every entry of every user in the current
 * namespace — the memory each user's agents share and each agent's own —
 * filterable by user, with a view and a delete per entry. For admins of the
 * namespace only: {@code /admin/memories} is not among the paths the access
 * guard opens to plain members.
 */
@RestController
@RequestMapping(MemoryAdminUiController.API)
public class MemoryAdminUiController {

    public static final String API = "/admin/api/memories";
    static final String PAGE = "/admin/memories";
    static final String SEARCH_FORM_ID = "memory-search";

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
    public UiPage list(@RequestParam(value = "user", required = false) String user) {
        return page(user);
    }

    /** The filter form: typing + Enter re-renders the page for the users whose id contains the text. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw) {
        Object q = raw == null ? null : raw.get("q");
        return page(q == null ? null : q.toString());
    }

    @GetMapping("/{row}")
    public UiPatch view(@PathVariable("row") String row) {
        if (memory == null) return notFound();
        return Row.parse(row)
                .flatMap(r -> memory.read(r.userId(), r.agentId(), r.name()))
                .map(entry -> UserMemoryUiController.dialog(entry.name(),
                        UserMemoryComponent.detail(entry, UserMemoryUiController.agentNames(agents))))
                .orElseGet(MemoryAdminUiController::notFound);
    }

    @DeleteMapping("/{row}")
    public UiPatch delete(@PathVariable("row") String row) {
        if (memory == null) return notFound();
        boolean deleted = Row.parse(row).map(r -> memory.delete(r.userId(), r.agentId(), r.name())).orElse(false);
        UiPatch patch = UiPatch.of()
                .patch(UiPatch.Operation.replace(UserMemoryComponent.ADMIN_TABLE_ID, table(null)));
        return deleted
                ? patch.toast(UiToast.success("The user's agents will no longer know it.").title("Memory deleted"))
                : patch.toast(UiToast.error("That memory is gone.").title("Not found"));
    }

    private UiPage page(String user) {
        UiStack body = UiStack.of("admin-memory").gap(16)
                .child(UiText.of("admin-memory-help",
                        "What agents have remembered about the users of this namespace across chats. "
                        + "\"Shared by all agents\" is a user's own memory, which every agent with the "
                        + "memory tools sees; an agent's memory only that agent sees. Users see and delete "
                        + "their own entries on their profile."))
                .child(searchForm(user));
        if (memory == null) {
            body.child(UiText.of("admin-memory-off", "This runtime keeps no memory."));
        } else {
            body.child(table(user));
        }
        return UiPage.of(PAGE, body);
    }

    private UiNode table(String user) {
        List<MemoryEntry> entries = memory == null ? List.of() : memory.listAll();
        if (user != null && !user.isBlank()) {
            String needle = user.strip().toLowerCase(Locale.ROOT);
            entries = entries.stream()
                    .filter(e -> e.userId().value().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        return UserMemoryComponent.table(UserMemoryComponent.ADMIN_TABLE_ID, API, entries,
                UserMemoryUiController.agentNames(agents), true);
    }

    private static UiForm searchForm(String user) {
        UiForm form = UiForm.of(SEARCH_FORM_ID, null);
        form.field(UiField.text("q", "", user == null ? "" : user)
                .asEditable()
                .icon("search")
                .placeholder("Filter by user…")
                .onChange(trigger(on(MemoryAdminUiController.class).search(null), SEARCH_FORM_ID)));
        return form;
    }

    private static UiPatch notFound() {
        return UiPatch.of().toast(UiToast.error("That memory is gone.").title("Not found"));
    }
}
