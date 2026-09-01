package ai.mindconnect.chatui.ui.controller;


import ai.mindconnect.chatui.ui.component.TaskCardComponent;
import ai.mindconnect.chatui.ui.page.ChatPage;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.model.UiAction;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@RestController
@RequestMapping("/chat/api")
public class ChatUiController {

    private static final Logger log = LoggerFactory.getLogger(ChatUiController.class);

    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final AgentDefinitionRepository agentRepository;
    private final AgentSessionRepository sessionRepository;
    private final TodoListService todoListService;
    private final WorkspaceStore workspaceStore;
    private final ObjectMapper objectMapper;
    /** Optional — null in setups where trace persistence is disabled. */
    private final ai.mindconnect.agent.port.out.LlmCallTraceRepository traceRepository;
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;
    private final ai.mindconnect.chatui.service.SessionStreams sessionStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;
    /** What the embedding app adds to the chat — none in a standalone chat app. */
    private final ai.mindconnect.chatui.ui.ChatHostLinks hostLinks;
    private final Namespace defaultNamespace;
    private final ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository;
    private final ai.mindconnect.agent.tool.ToolRegistry toolRegistry;
    private final ai.mindconnect.agent.service.SessionAgentResolver agentResolver;

    public ChatUiController(AgentSessionService sessionService,
                             AgentChatService chatService,
                             AgentDefinitionRepository agentRepository,
                             AgentSessionRepository sessionRepository,
                             TodoListService todoListService,
                             WorkspaceStore workspaceStore,
                             Namespace defaultNamespace,
                             ObjectMapper objectMapper,
                             ai.mindconnect.agent.port.out.LlmCallTraceRepository traceRepository,
                             ai.mindconnect.chatui.service.ActiveStreams activeStreams,
                             ai.mindconnect.chatui.service.SessionStreams sessionStreams,
                             ai.mindconnect.agentrest.service.SessionFileService sessionFiles,
                             ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore,
                             org.springframework.beans.factory.ObjectProvider<ai.mindconnect.chatui.ui.ChatHostLinks> hostLinks,
                             ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository,
                             ai.mindconnect.agent.tool.ToolRegistry toolRegistry) {
        this.sessionService = sessionService;
        this.sessionFiles = sessionFiles;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.todoListService = todoListService;
        this.workspaceStore = workspaceStore;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.activeStreams = activeStreams;
        this.sessionStreams = sessionStreams;
        this.approvalStore = approvalStore;
        this.hostLinks = hostLinks.getIfAvailable(() -> ai.mindconnect.chatui.ui.ChatHostLinks.NONE);
        this.defaultNamespace = defaultNamespace;
        this.llmConfigRepository = llmConfigRepository;
        this.toolRegistry = toolRegistry;
        this.agentResolver = new ai.mindconnect.agent.service.SessionAgentResolver(agentRepository);
    }


    /**
     * The attach dialog the chat form's "+" opens: the drop-zone in a modal,
     * patched over the untouched chat page. Uploads patch the page's
     * chat-attachments panel behind the dialog, so the chips are current
     * the moment it closes.
     */
    /**
     * The chat: the most recent conversation, or a fresh one when the user
     * has none. Never a form — a chat starts on the default model with the
     * default tools and is reconfigured from inside, not before.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<UiPage> home(@AuthenticationPrincipal OidcUser user) {
        var sessions = sessionRepository.findByUser(defaultNamespace, userId(user));
        if (sessions.isEmpty()) {
            // A GET does not create anything: a prefetch, a link preview or two
            // tabs opening at once would each leave an empty chat behind. The
            // empty state offers the same button the sidebar does.
            return ResponseEntity.ok(emptyShell());
        }
        return ResponseEntity.ok(shell(sessions.get(0), sessions));
    }

    /** What the chat looks like before there is anything to look at. */
    private UiPage emptyShell() {
        var invitation = ai.mindconnect.ui.model.UiList.of("chat-empty", null);
        invitation.item(ai.mindconnect.ui.model.UiList.Item
                .of("chat-empty-hint", "No conversations yet")
                .description("Start one and pick a model and tools from the composer."));
        invitation.action(UiAction.primary("start-first", "New chat").icon("add")
                .onClick(trigger(on(ChatUiController.class).createSession(null))));

        var appShell = new ai.mindconnect.chatui.ui.component.ChatShellComponent(
                List.of(), null, "Chat", invitation).render();
        return UiPage.of("/chat", appShell);
    }

    /** Starts a chat on the defaults and opens it. */
    @PostMapping("/sessions")
    public ResponseEntity<UiPage> createSession(@AuthenticationPrincipal OidcUser user) {
        String userId = userId(user);
        var session = openDefaultChat(userId);
        log.info("New chat {}", session.id());
        return ResponseEntity.ok(
                shell(session, sessionRepository.findByUser(defaultNamespace, userId)));
    }

    /** Model and tools of this chat, as a dialog over the conversation. */
    @GetMapping("/sessions/{sessionId}/settings")
    public ResponseEntity<UiPatch> settingsDialog(@PathVariable UUID sessionId,
                                                  @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var effective = agentResolver.resolve(session);
        UUID agentId = boundAgentId(session);

        var form = new ai.mindconnect.chatui.ui.component.ChatSettingsComponent(
                sessionId, llmConfigRepository.findAll(), selectableAgents(agentId), allToolNames(),
                effective.llmConfigName(),
                effective.tools().stream().map(ai.mindconnect.agent.tool.AgentTool::name).toList(),
                effective.toolSearchOrOff().enabled(), agentId, effective.systemPrompt()).render();

        var dlg = ai.mindconnect.ui.model.UiDialog.of("Model & tools", null, form);
        dlg.setId("chat-dialog");
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
    }

    /** Applies the dialog: either an agent takes over, or model and tools do. */
    @PostMapping("/sessions/{sessionId}/settings")
    public ResponseEntity<UiPage> applySettings(@PathVariable UUID sessionId,
                                                @RequestBody Map<String, Object> raw,
                                                @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var body = new FormBody(raw);
        String agentId = body.str("agentId");

        ai.mindconnect.agent.domain.session.SessionAgent agent;
        if (agentId != null && !agentId.isBlank()) {
            var def = agentRepository.findById(UUID.fromString(agentId))
                    .orElseThrow(() -> new IllegalArgumentException("No such agent: " + agentId));
            // Switching to another agent hands the chat over completely: that
            // agent's model, tools and prompt win, which is what the picker
            // promises. Staying on the same one keeps what this chat chose —
            // the ref carries exactly these three overrides for that.
            boolean sameAgent = def.id().equals(boundAgentId(sessionOpt.get()));

            // Only a value that actually differs is stored: an untouched field
            // must not turn into an override that then stops tracking edits to
            // the agent itself.
            String prompt = sameAgent ? differing(body.str("systemPrompt"), def.systemPrompt()) : null;
            String llm = sameAgent ? differing(body.str("llmConfigName"), def.llmConfigName()) : null;

            List<ai.mindconnect.agent.tool.AgentTool> toolOverride = null;
            ai.mindconnect.agent.domain.AgentDefinition.ToolSearchConfig searchOverride = null;
            if (sameAgent) {
                // Compared against what the dialog could actually offer, not
                // against everything the agent has: a tool the registry does
                // not know — Gmail without credentials — never reaches the
                // multiselect, so it can neither be kept nor removed there.
                List<String> chosen = body.strList("tools");
                var offerable = def.tools().stream()
                        .map(ai.mindconnect.agent.tool.AgentTool::name)
                        .filter(allToolNames()::contains)
                        .collect(java.util.stream.Collectors.toSet());
                if (chosen != null && !new java.util.HashSet<>(chosen).equals(offerable)) {
                    toolOverride = pickTools(def, chosen);
                }
                boolean search = body.bool("toolSearch", def.toolSearchOrOff().enabled());
                if (search != def.toolSearchOrOff().enabled()) {
                    searchOverride = new ai.mindconnect.agent.domain.AgentDefinition.ToolSearchConfig(
                            search, def.toolSearchOrOff().groups());
                }
            }
            agent = new ai.mindconnect.agent.domain.session.SessionAgentRef(
                    def.id(), true, def.name(), llm, toolOverride, searchOverride, prompt);
        } else {
            // Staying inline keeps the same agent — and therefore the same
            // workspace — while only the model and tools change. Coming from a
            // ref agent it is a new one, and the switch says so.
            var previous = sessionOpt.get().mainAgent().orElse(null);
            String prompt = body.str("systemPrompt");
            var fresh = inlineAgent(body.str("llmConfigName"),
                    body.strList("tools"), body.bool("toolSearch", true), prompt);
            agent = previous instanceof ai.mindconnect.agent.domain.session.InlineSessionAgent kept
                    ? kept.withLlmConfigName(fresh.llmConfigName())
                          .withTools(fresh.tools().stream()
                                  .map(ai.mindconnect.agent.tool.AgentTool::name).toList(),
                                  fresh.toolSearch().enabled())
                    : fresh;
        }
        var saved = sessionService.replaceSessionAgent(sessionId, agent);
        String userId = userId(user);
        return ResponseEntity.ok(
                shell(saved, sessionRepository.findByUser(defaultNamespace, userId)));
    }

    /** The rename dialog for one chat. */
    @GetMapping("/sessions/{sessionId}/rename")
    public ResponseEntity<UiPatch> renameDialog(@PathVariable UUID sessionId,
                                                @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        String current = sessionOpt.get().title();

        var form = ai.mindconnect.ui.model.UiForm.of("chat-rename-" + sessionId, "Rename chat")
                .field(ai.mindconnect.ui.model.UiField.text("title", "Title", current)
                        .asEditable().asRequired())
                .action(UiAction.primary("save", "Save").icon("save")
                        .onClick(trigger(on(ChatUiController.class).rename(sessionId, null, null),
                                "chat-rename-" + sessionId)))
                .action(UiAction.secondary("cancel", "Cancel")
                        .onClick(trigger(on(ChatUiController.class).closeDialog())));

        var dlg = ai.mindconnect.ui.model.UiDialog.of("Rename chat", null, form);
        dlg.setId("chat-dialog");
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
    }

    /** Applies a new title and redraws — the sidebar entry changes with it. */
    @PostMapping("/sessions/{sessionId}/rename")
    public ResponseEntity<UiPage> rename(@PathVariable UUID sessionId,
                                         @RequestBody Map<String, Object> raw,
                                         @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        String title = new FormBody(raw).str("title");
        if (title != null && !title.isBlank()) {
            sessionService.updateTitle(sessionId, title.trim());
        }
        String userId = userId(user);
        var sessions = sessionRepository.findByUser(defaultNamespace, userId);
        var current = sessionRepository.findById(sessionId).orElseThrow();
        return ResponseEntity.ok(shell(current, sessions));
    }

    /**
     * Deletes a chat and opens the next one — or a fresh chat when that was
     * the last. The conversation goes with it; there is nothing left to show.
     */
    @PostMapping("/sessions/{sessionId}/delete")
    public ResponseEntity<UiPage> deleteSession(@PathVariable UUID sessionId,
                                                @AuthenticationPrincipal OidcUser user) {
        String userId = userId(user);
        if (ownedSession(sessionId, user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        sessionService.deleteSession(sessionId);
        log.info("Chat {} deleted", sessionId);

        var sessions = sessionRepository.findByUser(defaultNamespace, userId);
        if (sessions.isEmpty()) {
            var fresh = openDefaultChat(userId);
            return ResponseEntity.ok(shell(fresh, List.of(fresh)));
        }
        return ResponseEntity.ok(shell(sessions.get(0), sessions));
    }

    /**
     * The session, but only for the user it belongs to. Every endpoint that
     * addresses a session by id goes through here: the id is the only thing
     * standing between one user's chat and another's, and an id is not a
     * secret — it travels in URLs, links and logs.
     */
    private java.util.Optional<ai.mindconnect.agent.domain.AgentSession> ownedSession(
            UUID sessionId, OidcUser user) {
        String userId = userId(user);
        return sessionRepository.findById(sessionId)
                .filter(session -> userId.equals(session.userId()));
    }

    /** Closes the settings dialog without touching anything. */
    @PostMapping("/close-dialog")
    public ResponseEntity<UiPatch> closeDialog() {
        return ResponseEntity.ok(UiPatch.of().patch(UiPatch.Operation.remove("chat-dialog")));
    }

    // ── Building the shell ──────────────────────────────────────────────────

    /** The chat app shell: history left, agent and title on top, conversation. */
    private UiPage shell(ai.mindconnect.agent.domain.AgentSession session,
                         List<ai.mindconnect.agent.domain.AgentSession> sessions) {
        var agent = agentResolver.resolve(session);
        var chat = buildChatPage(session, agent);
        var appShell = new ai.mindconnect.chatui.ui.component.ChatShellComponent(
                sessions, session, agent.name(), chat.renderContent(), agentIcons()).render();
        var page = UiPage.of("/chat/sessions/" + session.id(), appShell);
        // A reload during a live turn reattaches instead of showing a dead form.
        if (!chat.activeStreams().isEmpty()) {
            page.setActiveStreams(chat.activeStreams());
        }
        return page;
    }

    /** The seeded agent a chat runs on when it did not come from one. */
    private static final String DEFAULT_CHAT_AGENT = "default-chat";

    /**
     * Opens a chat that nobody started from an agent page.
     *
     * <p>It runs on the seeded {@code default-chat} agent where the namespace
     * has one, so its prompt, its model, its tools and the roster it may
     * delegate to are configuration like every other agent's — changed in the
     * admin UI rather than compiled in here. That is the point: the defaults
     * of the chat everyone lands in should not be the one thing you cannot
     * edit.
     *
     * <p>A namespace seeded before that agent existed has no such definition,
     * and falls back to the inline agent this controller has always built. So
     * upgrading changes nothing until the agent is installed.
     */
    private ai.mindconnect.agent.domain.AgentSession openDefaultChat(String userId) {
        return agentRepository.findByName(defaultNamespace, DEFAULT_CHAT_AGENT)
                .map(a -> sessionService.openChat(a.id(), defaultNamespace, userId))
                .orElseGet(() -> sessionService.openChat(inlineDefaultChatAgent(),
                        defaultNamespace, userId));
    }

    /** The fallback chat agent: the standard model, the standard tools. */
    private ai.mindconnect.agent.domain.session.InlineSessionAgent inlineDefaultChatAgent() {
        return inlineAgent(defaultLlmConfigName(),
                ai.mindconnect.chatui.ui.component.ChatSettingsComponent.DEFAULT_TOOLS, true);
    }

    /** The session's own agent, built from a model name and tool names. */
    private ai.mindconnect.agent.domain.session.InlineSessionAgent inlineAgent(
            String llmConfigName, List<String> tools, boolean toolSearch) {
        return inlineAgent(llmConfigName, tools, toolSearch, null);
    }

    /** @param systemPrompt {@code null} or blank falls back to the built-in one. */
    private ai.mindconnect.agent.domain.session.InlineSessionAgent inlineAgent(
            String llmConfigName, List<String> tools, boolean toolSearch, String systemPrompt) {
        List<String> names = tools == null || tools.isEmpty()
                ? ai.mindconnect.chatui.ui.component.ChatSettingsComponent.DEFAULT_TOOLS
                : tools;
        var known = allToolNames();
        return ai.mindconnect.agent.domain.session.InlineSessionAgent.of(
                "Chat", systemPrompt == null || systemPrompt.isBlank() ? CHAT_SYSTEM_PROMPT : systemPrompt,
                llmConfigName == null ? defaultLlmConfigName() : llmConfigName,
                names.stream().filter(known::contains).toList(),
                toolSearch);
    }

    /** The default prompt of a chat that has no agent behind it. */
    private static final String CHAT_SYSTEM_PROMPT = """
            You are a helpful assistant. Be concise and practical.

            Today's date: {{ current_date }}

            You can call specialised sub-agents with `run_agent` when a task
            needs one — `list_agents` shows which exist. Use the workspace
            tools to keep notes across the conversation, and `todo_write` to
            publish a plan before starting anything with several steps.
            """;

    /**
     * The agents a person may pick for a chat: the ones filed under
     * {@code assistants}. The others are not for chatting with — a sub-agent
     * expects a self-contained brief from an orchestrator and has no memory of
     * a conversation, and a utility like the title generator answers in the one
     * shape the runtime calls it for. Offering all sixteen made the picker a
     * list of things that mostly disappoint when you pick them.
     *
     * <p>The chat's current agent stays in the list even when it is not an
     * assistant, so opening the dialog on such a chat and pressing Apply does
     * not silently reassign it.
     */

    /** The submitted value when it says something other than the agent's own. */
    private static String differing(String submitted, String agentsOwn) {
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        return submitted.strip().equals(String.valueOf(agentsOwn).strip()) ? null : submitted;
    }

    /**
     * The chat's tool selection as {@link ai.mindconnect.agent.tool.AgentTool}s,
     * reusing the agent's own binding for every name it already has.
     *
     * <p>Rebuilding them from bare names would quietly drop what the binding
     * carries beyond the name — {@code needsApproval} on bash and
     * {@code code_execute}, the {@code mountDir} that gives the sandbox its
     * host directory, a deferred flag. Turning the model dropdown would have
     * been enough to lose all of it.
     */
    private List<ai.mindconnect.agent.tool.AgentTool> pickTools(
            ai.mindconnect.agent.domain.AgentDefinition def, List<String> names) {
        var byName = def.tools().stream().collect(java.util.stream.Collectors.toMap(
                ai.mindconnect.agent.tool.AgentTool::name, t -> t, (a, b) -> a));
        var known = allToolNames();
        var picked = new java.util.LinkedHashMap<String, ai.mindconnect.agent.tool.AgentTool>();
        // Whatever the dialog could not show stays: the chat did not drop it,
        // it was never asked about. Without this, opening the settings and
        // pressing Apply would silently strip an agent's Gmail tools on a
        // machine where Gmail is not configured.
        for (var t : def.tools()) {
            if (!known.contains(t.name())) {
                picked.put(t.name(), t);
            }
        }
        for (String n : names) {
            if (!known.contains(n)) {
                continue;
            }
            picked.put(n, byName.containsKey(n)
                    ? byName.get(n)
                    : ai.mindconnect.agent.tool.AgentTool.of(def.id(), n));
        }
        return List.copyOf(picked.values());
    }
    /**
     * The registry agent this chat runs on, or {@code null} for a chat with an
     * agent of its own.
     *
     * <p>Two shapes mean the same thing. Picking an agent in the settings
     * dialog writes a {@link ai.mindconnect.agent.domain.session.SessionAgentRef};
     * opening a chat from an agent — which is now every chat, via
     * {@code default-chat} — sets only {@code agentDefinitionId} and leaves the
     * session-agent list empty. Reading just the ref reported "no agent" for
     * the second kind, and pressing Apply on one detached the chat from the
     * agent it was plainly running on.
     *
     * <p>The id is checked against the registry: an inline agent's id is minted
     * for the session and would otherwise look like a binding.
     */
    private UUID boundAgentId(ai.mindconnect.agent.domain.AgentSession session) {
        UUID ref = session.mainAgent()
                .filter(a -> a instanceof ai.mindconnect.agent.domain.session.SessionAgentRef)
                .map(ai.mindconnect.agent.domain.session.SessionAgent::id)
                .orElse(null);
        if (ref != null) {
            return ref;
        }
        UUID fromSession = session.agentDefinitionId();
        return fromSession != null && agentRepository.findById(fromSession).isPresent()
                ? fromSession : null;
    }

    /**
     * Icon name per agent-definition id, for the history drawer. Read from the
     * registry in one go: a row only needs the icon, and resolving every
     * session's agent separately would be one lookup per conversation.
     */
    private java.util.Map<UUID, String> agentIcons() {
        var icons = new java.util.HashMap<UUID, String>();
        for (AgentDefinition a : agentRepository.findByNamespace(defaultNamespace)) {
            icons.put(a.id(), a.iconOrDefault());
        }
        return icons;
    }

    private List<AgentDefinition> selectableAgents(UUID currentAgentId) {
        return agentRepository.findByNamespace(defaultNamespace).stream()
                .filter(a -> a.status() != ai.mindconnect.agent.domain.AgentDefinitionStatus.DEPRECATED)
                .filter(a -> CHAT_GROUP.equals(a.groupOrDefault()) || a.id().equals(currentAgentId))
                .toList();
    }

    /** The one rubric whose agents a person opens a chat with. */
    private static final String CHAT_GROUP = "assistants";

    /** Everything a chat can be given: the registry plus the runtime's own two. */
    private List<String> allToolNames() {
        var names = new java.util.TreeSet<String>();
        toolRegistry.toolNamesByGroup().values().forEach(names::addAll);
        names.add(ai.mindconnect.agent.service.InlineAgentTools.RUN_AGENT);
        names.add(ai.mindconnect.agent.service.InlineAgentTools.RUN_AGENTS);
        return List.copyOf(names);
    }

    /**
     * The model a chat starts on: the {@code agent-default} config when there
     * is one — it exists precisely as a swappable pointer at "the default" —
     * otherwise the first configured model.
     */
    private String defaultLlmConfigName() {
        var names = llmConfigRepository.findAll().stream()
                .map(ai.mindconnect.llm.domain.LlmConfig::name)
                .toList();
        return names.stream().filter("agent-default"::equals).findFirst()
                .or(() -> names.stream().findFirst())
                .orElse(null);
    }

    private static String userId(OidcUser user) {
        return user == null ? "mc_user" : user.getPreferredUsername();
    }

    @PostMapping("/agents/{agentId}/sessions")
    public ResponseEntity<UiPage> startSession(@PathVariable UUID agentId,
                                               @AuthenticationPrincipal OidcUser user) {
        String userId = user.getPreferredUsername();
        return agentRepository.findById(agentId)
                .map(agent -> {
                    var session = sessionService.openChat(agentId, agent.namespace(), userId);
                    return ResponseEntity.ok(shell(session,
                            sessionRepository.findByUser(defaultNamespace, userId)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{sessionId}/attach-dialog")
    public ResponseEntity<UiPatch> attachDialog(@PathVariable UUID sessionId,
                                                @AuthenticationPrincipal OidcUser user) {
        if (ownedSession(sessionId, user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // The dialog carries both halves: what is already attached, and the
        // drop zone to add more. Uploads patch the list in place, so it stays
        // open and current while files arrive.
        var body = ai.mindconnect.ui.model.UiStack.of("chat-attach-body");
        body.gap(12);
        body.child(ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent
                .node(sessionId, sessionFiles.listAttachments(sessionId)));
        body.child(ai.mindconnect.chatui.ui.page.ChatPage.attachZone(sessionId));
        var dlg = ai.mindconnect.ui.model.UiDialog.of("Attached files", null, body);
        dlg.setId("chat-dialog");
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<UiPage> getSession(@PathVariable UUID sessionId,
                                             @AuthenticationPrincipal OidcUser user) {
        return ownedSession(sessionId, user)
                .map(session -> ResponseEntity.ok(shell(session,
                        sessionRepository.findByUser(defaultNamespace, userId(user)))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Loads the current history + memory snapshot for the session and
     * wraps them in a {@link ChatPage} bound to the given agent. All
     * chat-page rendering and patch generation goes through this single
     * factory so the controller never reaches for the model directly.
     */
    private ChatPage buildChatPage(ai.mindconnect.agent.domain.AgentSession session,
                                    ai.mindconnect.agent.domain.AgentDefinition agent) {
        var history = sessionService.loadHistory(session.id());
        var memory  = safeMemorySnapshot(session.id());
        // Source-of-truth for "is this turn currently streaming?" is the
        // registry — not page-local state. Lets a navigate-back during a
        // live turn render the form in Stop-mode without any client-side
        // reconciliation.
        String channelId = "msg-list-" + session.id();
        var handleOpt = activeStreams.findHandle(channelId);
        var page = new ChatPage(session, agent, history, memory, handleOpt.isPresent(),
                (toolCallId, running, in, out) ->
                        buildSubAgentCards(session.id(), toolCallId, running, in, out))
                .withBubbledApprovals(bubbledApprovalCards(session.id()))
                .withHostLinks(hostLinks);
        // Every render hands the SPA this session's stream — whether or not
        // a turn is running. That is the whole point: a client with nothing
        // to listen to cannot find out that someone else started a turn, so
        // it attaches while the session is quiet and stays attached.
        //
        // The cursor matters. This page already renders everything the
        // stream has published so far, and the patches are APPENDs, so a
        // replay from 0 would add the user message and the task cards a
        // second time. Asking for what comes AFTER the current position is
        // the only correct request; a client joining mid-turn is brought up
        // to date by the catch-up frames instead (see StreamController).
        long from = sessionStreams.find(channelId)
                .map(ai.mindconnect.chatui.service.StreamBus::lastSeq).orElse(0L);
        String agentLabel = agent.name() != null ? agent.name() : "Agent";
        // What to call this page on a surface that links back to it. The
        // framework must not guess: it has no idea it is streaming a chat.
        // The session's own title first, the agent's name while the chat is
        // still untitled.
        String returnLabel = session.title() != null && !session.title().isBlank()
                ? session.title() : agentLabel;
        page.withActiveStreams(java.util.List.of(
                ai.mindconnect.ui.model.UiPage.ActiveStream.of(
                        channelId,
                        "/chat/api/streams/" + channelId + "/sse?from=" + from,
                        handleOpt.map(ai.mindconnect.chatui.service.ActiveStreams.Handle::label)
                                .orElse(agentLabel),
                        "/chat/sessions/" + session.id(),
                        returnLabel)));
        return page;
    }

    /**
     * The cards for this session's OPEN sub-agent approval questions — read
     * from the ToolApprovalStore, the single truth for bubbled requests
     * (entry exists = card shows; answered/cancelled/deleted = entry gone).
     */
    private List<ai.mindconnect.ui.model.UiList.Item> bubbledApprovalCards(UUID sessionId) {
        return approvalStore.openForRoot(sessionId).stream()
                .map(open -> {
                    var call = ai.mindconnect.chatui.ui.component.ApprovalCardComponent
                            .parseApprovalContent(open.content());
                    return ai.mindconnect.chatui.ui.component.ApprovalCardComponent.approvalCard(
                            sessionId, open.callId(), call.toolName(), call.argsJson(),
                            ai.mindconnect.chatui.ui.SessionUiCommons.DT_FMT
                                    .format(open.requestedAt()));
                })
                .toList();
    }


    /**
     * Rebuilds the nested sub-agent card tree for a single parent
     * {@code toolCallId} on a full page render.
     *
     * <p>Finds every sub-session that {@code parentSessionId} spawned via
     * that exact tool call (matched on {@code parentToolCallId} — works for
     * parallel {@code run_agents} batches, where several sessions share one
     * tool-call id), loads each one's history, and builds a
     * {@link TaskCardComponent} whose body is that sub-agent's own
     * (recursively-built) task tree plus its final answer. Recursion bottoms
     * out naturally when a sub-session spawned no further sub-agents.
     *
     * <p>{@code running} is supplied by the caller and reflects whether the
     * parent has a persisted TOOL_RESULT for this call yet — NOT the child
     * session's own status, which is unreliable (sessions are never moved
     * out of {@code ACTIVE} on disk). When done, "failed" is inferred from
     * the sub-agent having produced no final assistant text.
     */
    private List<TaskCardComponent> buildSubAgentCards(UUID parentSessionId, String toolCallId,
                                                       boolean running, String inputJson, String resultText) {
        if (toolCallId == null || toolCallId.isBlank()) return List.of();
        List<ai.mindconnect.agent.domain.AgentSession> children;
        try {
            children = sessionRepository.findByParentSessionId(parentSessionId).stream()
                    .filter(s -> toolCallId.equals(s.parentToolCallId()))
                    .sorted(java.util.Comparator.comparing(
                            ai.mindconnect.agent.domain.AgentSession::startedAt))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list sub-sessions of {} for toolCall {}: {}",
                    parentSessionId, toolCallId, e.getMessage());
            return List.of();
        }

        List<TaskCardComponent> cards = new java.util.ArrayList<>();
        for (var child : children) {
            try {
                var childAgent = agentRepository.findById(child.agentDefinitionId()).orElse(null);
                String agentName = childAgent != null ? childAgent.name() : "sub-agent";
                List<Message> childHistory = sessionService.loadHistory(child.id());

                // A throwaway component reuses all the historic-card grouping
                // logic for the sub-session, recursing through the same
                // provider scoped to THIS child's id. A nested sub-agent's
                // running state likewise follows its own parent-result
                // presence, computed inside that recursive call.
                var childComp = new ai.mindconnect.chatui.ui.component.MessageListComponent(
                        child.id(), childAgent, childHistory, null,
                        (tcId, r, in, out) -> buildSubAgentCards(child.id(), tcId, r, in, out));

                var childList = TaskCardComponent.subAgentChildList(child.id().toString());
                for (TaskCardComponent t : childComp.allHistoricTaskCards()) {
                    childList.item(((UiList) t.render()).getItems().get(0));
                }

                // Per-child Input: the sub-agent's own first user message (the
                // task it was given) reads better than the shared parent args,
                // especially for run_agents batches. Fall back to the parent's
                // call args when the child has no user message yet.
                String childInput = firstUserText(childHistory);
                if (childInput == null || childInput.isBlank()) childInput = inputJson;

                // While still running, hold back the answer block — it isn't
                // final yet (the live done patch appends it on completion).
                String finalText = running ? null : childComp.lastAssistantText();
                boolean failed = !running && (finalText == null || finalText.isBlank());
                // Node id MUST match the live stream's id (task-sub-{sessionId})
                // so a reload mid-run produces the same <li> and the run's
                // continuing live patches keep landing on it.
                String nodeId = "task-sub-" + child.id();
                cards.add(TaskCardComponent.historicSubAgent(
                        nodeId, agentName, child.id().toString(),
                        running, failed, durationOf(child), childInput, resultText,
                        childList, finalText));
            } catch (Exception e) {
                log.warn("Failed to build sub-agent card for session {}: {}",
                        child.id(), e.getMessage());
            }
        }
        return cards;
    }

    /** Wall-clock duration of a (completed) session in ms, or 0 when unknown. */
    private static long durationOf(ai.mindconnect.agent.domain.AgentSession s) {
        if (s.startedAt() == null || s.completedAt() == null) return 0L;
        return java.time.Duration.between(s.startedAt(), s.completedAt()).toMillis();
    }

    /** The first USER CHAT message in a (sub-)session — the task it was given. */
    private static String firstUserText(List<Message> history) {
        return history.stream()
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER)
                .min(java.util.Comparator.comparingInt(Message::sequenceNum))
                .map(Message::content)
                .orElse(null);
    }

    @PostMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<UiPatch> chat(@PathVariable UUID sessionId,
                                        @RequestBody Map<String, Object> raw,
                                        @AuthenticationPrincipal OidcUser user) {
        var body    = new FormBody(raw);
        String text = body.str("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agent = agentOpt.get();

        try (var ctx = LoggingContext.session(sessionId, null, agent.name())) {
            ChatTurnHandle turn = chatService.submitChat(sessionId, text, streamLogger(agent.name()));
            turn.result().join();
        }

        return ResponseEntity.ok(buildChatPage(sessionOpt.get(), agent).chatTurnComplete());
    }

    /**
     * Cooperatively cancels a running chat turn. Returns 204 if a live turn
     * was signalled, 404 if no chat is currently running. The stream
     * completes via the normal {@code Done} flow once the loop reaches its
     * next cancel-check point.
     */
    @DeleteMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<Void> cancelChat(@PathVariable UUID sessionId) {
        boolean cancelled = chatService.cancelChat(sessionId);
        log.info("DELETE /chat/api/sessions/{}/chat → cancelled={}", sessionId, cancelled);
        return cancelled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Deletes a range of messages by sequenceNum ({@code fromSeq}..{@code toSeq}
     * inclusive). The UI's "delete from here" button passes
     * {@code toSeq=Integer.MAX_VALUE} to drop this message and everything after
     * it. Returns a UI patch that refreshes the conversation list (with the
     * updated header tokens) so the deleted items disappear. Sub-agent sessions
     * spawned by the removed turns are not cleaned up.
     */
    @DeleteMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<UiPatch> deleteMessages(@PathVariable UUID sessionId,
                                                   @RequestParam int fromSeq,
                                                   @RequestParam int toSeq,
                                                   @AuthenticationPrincipal OidcUser user) {
        log.info("DELETE /chat/api/sessions/{}/messages fromSeq={} toSeq={}", sessionId, fromSeq, toSeq);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();

        sessionService.deleteMessages(sessionId, fromSeq, toSeq);

        return ResponseEntity.ok(
                buildChatPage(sessionOpt.get(), agentOpt.get()).headerOnly());
    }

    @PostMapping("/sessions/{sessionId}/chat/stream")
    public ResponseEntity<ai.mindconnect.ui.model.UiPatch> chatStream(@PathVariable UUID sessionId,
                                                 @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String text = body.str("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return runChatStream(sessionOpt.get(), agentOpt.get(), text, false);
    }

    /**
     * The answer to ANY approval card. The callId is the whole identity —
     * tool task, tool name and origin live in the ToolApprovalStore. No new
     * stream: the turn never ended (it is suspended on the parked tool task)
     * and its original stream carries the continuation; this delivers the
     * decision and refreshes the list so the card disappears. A STALE card
     * (no store entry any more) delivers nothing — the refresh alone drops it.
     */
    @PostMapping("/sessions/{sessionId}/approval")
    public ResponseEntity<UiPatch> approvalAnswered(@PathVariable UUID sessionId,
                                                    @RequestParam String callId,
                                                    @RequestParam boolean approved,
                                                    @RequestParam(defaultValue = "once") String scope,
                                                    @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        boolean delivered = chatService.answerApproval(sessionId, callId, approved,
                ai.mindconnect.agent.service.approval.ApprovalScope.fromParam(scope));
        log.info("POST /chat/api/sessions/{}/approval call={} approved={} scope={} delivered={}",
                sessionId, callId, approved, scope, delivered);
        return ResponseEntity.ok(buildChatPage(sessionOpt.get(), agentOpt.get()).headerOnly());
    }

    /**
     * Regenerates the assistant response for a user message: deletes that
     * message and everything after it, then re-runs the turn (streaming) with
     * the same user text. The new user message and reply are persisted fresh.
     *
     * <p>Only meaningful on USER messages — the UI exposes the button there.
     * Sub-agent sessions spawned by the discarded turns are intentionally
     * left in place (not cleaned up).
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/{seq}/regenerate")
    public ResponseEntity<ai.mindconnect.ui.model.UiPatch> regenerate(@PathVariable UUID sessionId,
                                                 @PathVariable int seq) {
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Capture the user text at `seq` before we delete it.
        String text = sessionService.loadHistory(sessionId).stream()
                .filter(m -> m.sequenceNum() == seq)
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER)
                .map(Message::content)
                .findFirst()
                .orElse(null);
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Drop this message and everything after it; the turn below re-adds
        // the user message fresh, so deleting from `seq` inclusive avoids a
        // duplicate. toSeq = MAX_VALUE → delete to the end.
        sessionService.deleteMessages(sessionId, seq, Integer.MAX_VALUE);
        log.info("Regenerate session={} from seq={} (deleted to end, re-running turn)", sessionId, seq);

        // initialRefresh=true: push the trimmed conversation to the client
        // before streaming, so the now-deleted messages disappear from the
        // DOM instead of lingering until the end-of-turn refresh.
        return runChatStream(sessionOpt.get(), agentOpt.get(), text, true);
    }

    /**
     * Shared SSE streaming core for a chat turn: sets up the per-channel bus,
     * registers the active stream, submits {@code text} as a turn, and emits
     * UiPatch frames as tokens / task-cards / sub-agent trees arrive. Used by
     * both {@link #chatStream} and {@link #regenerate}.
     *
     * @param initialRefresh when true, a full message-list refresh is pushed
     *                       before the turn starts — used by regenerate so the
     *                       just-deleted messages leave the DOM immediately.
     */
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runChatStream(ai.mindconnect.agent.domain.AgentSession session,
                                                     ai.mindconnect.agent.domain.AgentDefinition agent,
                                                     String text, boolean initialRefresh) {
        return runTurnStream(session, agent, text, initialRefresh,
                handler -> chatService.submitChat(session.id(), text, handler));
    }

    /**
     * The streaming core, parameterised over WHAT starts the turn: a typed
     * message ({@code text} echoed as a user bubble) or an approval answer
     * ({@code text == null} — the card click is the input, nothing to echo).
     */
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runTurnStream(ai.mindconnect.agent.domain.AgentSession session,
                                                     ai.mindconnect.agent.domain.AgentDefinition agent,
                                                     String text, boolean initialRefresh,
                                                     java.util.function.Function<java.util.function.Consumer<StreamEvent>, ChatTurnHandle> turnStarter) {
        UUID sessionId = session.id();
        // Channel id == the id of the message-list container the patches
        // target. This way the client's {@code findStreamTarget} lookup
        // naturally detects "chat page mounted", and both the submitter and
        // any observer resolve the same stream.
        String channelId = "msg-list-" + sessionId;
        String returnHref = "/chat/sessions/" + sessionId;
        String streamLabel = agent.name() != null ? agent.name() : "Agent";
        String pendingId  = "bot-pending-"  + sessionId;
        String thinkingId = "bot-thinking-" + sessionId;

        // The streaming-time page is built once with the pre-turn history;
        // it owns the form / message-list / task-card patch shapes the
        // event handler emits. The final refresh after the turn rebuilds
        // a fresh page from the post-turn history so the rendered list
        // reflects what's actually been persisted.
        ChatPage liveView = buildChatPage(session, agent);

        // The turn does not stream back to whoever submitted it. It
        // publishes into the SESSION's stream, which every client of this
        // session is already attached to — the submitter included. One path
        // for everyone is what makes a second client see the same tokens at
        // the same time, and it means this request can return as soon as the
        // turn is queued.
        var bus = sessionStreams.turnStarted(channelId);

        // Register the stream so the chat-page renderer (and the generic
        // /chat/api/streams endpoint) can see "this session is streaming".
        // Cancellation goes through the same chatService.cancelChat path
        // the legacy DELETE /sessions/{id}/chat endpoint uses — no separate
        // mechanism to keep in sync.
        activeStreams.register(new ai.mindconnect.chatui.service.ActiveStreams.Handle(
                channelId,
                streamLabel,
                returnHref,
                java.time.Instant.now(),
                () -> chatService.cancelChat(sessionId),
                bus));

        // 0. Regenerate only: replace the message list with the trimmed
        //    (post-delete) history so the discarded messages vanish before the
        //    new user message + reply stream in.
        if (initialRefresh) {
            publishPatch(bus, liveView.headerOnly());
        }

        // 1. Append user message (a typed turn) or just swap the form to
        //    streaming (an approval resume), add thinking indicator.
        publishPatch(bus, text != null
                ? liveView.streamStart(text, thinkingId)
                : liveView.streamResume());

        // 2. Stream tokens + per-task cards.
        StringBuilder cumulativeText = new StringBuilder();
        /** Tracks whether the bot-pending item has been appended yet. */
        final boolean[] pendingAppended = new boolean[]{false};

        // Per-task state, keyed by tool-call id (regular tools) or sub-agent
        // taskId (run_agent). Insertion order = render order in the chat.
        java.util.LinkedHashMap<String, LiveTask> liveTasks = new java.util.LinkedHashMap<>();
        /** Holds the id of the currently-open card so it can be collapsed when a new one starts. */
        final String[] openTaskNodeId = new String[]{null};
        // Maps an ephemeral run taskId → the durable sub-session id. All
        // sub-agent card/list ids are keyed on the session id, so a page
        // reload mid-run rebuilds the same nodes from persisted state and the
        // run's continuing live patches still land. Child tool events are
        // correlated only by taskId, so this is how we resolve their
        // container's session-keyed id.
        java.util.Map<java.util.UUID, java.util.UUID> taskToSession = new java.util.concurrent.ConcurrentHashMap<>();

        ChatTurnHandle turn = turnStarter.apply(event -> {
            switch (event) {
                case StreamEvent.Token t -> {
                    cumulativeText.append(t.text());
                    if (!pendingAppended[0]) {
                        // First token: drop the thinking indicator and append
                        // the streaming bot-reply placeholder BELOW any task
                        // cards that arrived during the thinking phase.
                        // Kept as the catch-up frame: a client that opens the
                        // page mid-turn has no bubble, and every token after
                        // it is a REPLACE that would land nowhere.
                        sessionStreams.rememberBubble(channelId,
                                publishPatch(bus, liveView.streamFirstToken(pendingId, thinkingId)));
                        pendingAppended[0] = true;
                    }
                    // Token patches carry the CUMULATIVE text, so the newest
                    // one alone restores the full reply so far.
                    sessionStreams.rememberText(channelId,
                            publishPatch(bus, liveView.streamToken(pendingId, cumulativeText.toString())));
                }
                case StreamEvent.ApprovalRequested ar -> {
                    // Durable already (request message / store entry written
                    // first); this is the live mirror: push the card into the
                    // open stream. Bubbled when an origin session rode along.
                    String argsJson;
                    try {
                        argsJson = ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER
                                .writerWithDefaultPrettyPrinter().writeValueAsString(ar.arguments());
                    } catch (Exception e) {
                        argsJson = String.valueOf(ar.arguments());
                    }
                    var card = ai.mindconnect.chatui.ui.component.ApprovalCardComponent.approvalCard(
                            sessionId, ar.callId(), ar.toolName(), argsJson,
                            ai.mindconnect.chatui.ui.SessionUiCommons.DT_FMT
                                    .format(java.time.Instant.now()));
                    publishPatch(bus, liveView.appendApprovalCard(card));
                }
                case StreamEvent.ToolCallStarted s ->
                    startToolCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            null, s.toolName(), s.arguments());
                case StreamEvent.ToolCallResult r ->
                    finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                            null, r.toolName(), r.result(), r.durationMs(), false);
                case StreamEvent.ToolCallFailed f ->
                    finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                            null, f.toolName(), f.error(), f.durationMs(), true);
                case StreamEvent.SubAgentStarted s ->
                    startSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            null, s.taskId(), s.agentName(), s.subSessionId(), s.input());
                case StreamEvent.SubAgentDone sd ->
                    finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            sd.taskId(), sd.agentName(), sd.finalText(), null);
                case StreamEvent.SubAgentError sErr ->
                    finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            sErr.taskId(), sErr.agentName(), null, sErr.error());
                case StreamEvent.SubAgentEvent wrapper ->
                    handleSubAgentInner(wrapper, liveView, liveTasks, openTaskNodeId, bus, taskToSession);
                default -> {}
            }
            logEvent(event, agent.name());
        });

        // 3. When the turn completes, replace placeholders with persisted
        //    final bot message + collapsed activity, then send the done event.
        turn.result().whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause = (error.getCause() != null) ? error.getCause() : error;
                log.error("SSE chat error", cause);
                // The SSE response is already committed (headers + initial events
                // flushed), so we can't surface this as an HTTP 500 — Spring's
                // exception resolver would crash on a content-type mismatch
                // ("No converter ... text/event-stream"). Instead emit a custom
                // 'error' event with a human-readable message and complete the
                // emitter normally; the browser sees a clean stream end.
                String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                try {
                    publishPatch(bus, liveView.streamError(message));
                } catch (Exception ignored) {}
                try {
                    bus.publish("error", message);
                } catch (Exception ignored) {}
                // The stream stays open — it belongs to the session, not to
                // this turn. Only the "a turn is running" entry goes, so the
                // next page render shows Send instead of Stop.
                activeStreams.deregister(channelId);
                sessionStreams.turnEnded(channelId);
                return;
            }
            try {
                // Build a fresh page from the post-turn history so the
                // streamDone() patch reflects what's actually been persisted
                // (assistant message, historic task cards, updated tokens).
                ChatPage finalView = buildChatPage(session, agent);
                publishPatch(bus, finalView.streamDone());

                // "done" ends the TURN, not the stream: subscribers stay
                // attached and are still there when the next turn — possibly
                // started by another client — begins.
                bus.publish("done", "");
            } catch (Exception e) {
                log.error("SSE chat finalize error", e);
            } finally {
                // Drop the "a turn is running" entry so subsequent page
                // renders show Send instead of Stop. Subscribers saw the
                // final patch and the done event before this fires.
                activeStreams.deregister(channelId);
                sessionStreams.turnEnded(channelId);
            }
        });

        // Nothing to hand back: the turn's output travels on the session
        // stream this client is already reading.
        return ResponseEntity.ok(ai.mindconnect.ui.model.UiPatch.of());
    }

    /** Per-task state held while a turn is streaming. */
    private static final class LiveTask {
        final String nodeId;
        final String name;
        final boolean isSubAgent;
        /** For tasks nested inside a sub-agent: the immediate parent sub-agent's taskId. {@code null} for top-level tasks. */
        final java.util.UUID parentTaskId;
        java.util.Map<String, Object> input;   // may be null for sub-agents (no args at start)
        String output;
        long durationMs;
        boolean done;

        LiveTask(String nodeId, String name, boolean isSubAgent,
                 java.util.Map<String, Object> input, java.util.UUID parentTaskId) {
            this.nodeId = nodeId;
            this.name = name;
            this.isSubAgent = isSubAgent;
            this.input = input;
            this.parentTaskId = parentTaskId;
        }
    }

    /**
     * Unwraps a (possibly multiply-nested) {@link StreamEvent.SubAgentEvent}
     * and routes the innermost real event to the same card helpers used for
     * top-level events. The key difference from the flat-indent past: the
     * <em>immediate</em> parent sub-agent — the taskId of the innermost
     * wrapper — becomes the card's {@code parentTaskId}, so the card is
     * appended into that sub-agent's nested child list
     * ({@code subtasks-{taskId}}) and the DOM hierarchy mirrors the agent
     * call hierarchy.
     */
    private void handleSubAgentInner(StreamEvent.SubAgentEvent topWrapper,
                                      ChatPage liveView,
                                      java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                      String[] openTaskNodeId,
                                      ai.mindconnect.chatui.service.StreamBus bus,
                                      java.util.Map<java.util.UUID, java.util.UUID> taskToSession) {
        // Walk to the innermost real event; the last wrapper taskId is the
        // immediate parent sub-agent that owns the event.
        java.util.UUID parentTaskId = topWrapper.taskId();
        StreamEvent inner = topWrapper.inner();
        while (inner instanceof StreamEvent.SubAgentEvent next) {
            parentTaskId = next.taskId();
            inner = next.inner();
        }

        switch (inner) {
            case StreamEvent.ToolCallStarted s ->
                startToolCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        parentTaskId, s.toolName(), s.arguments());
            case StreamEvent.ToolCallResult r ->
                finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                        parentTaskId, r.toolName(), r.result(), r.durationMs(), false);
            case StreamEvent.ToolCallFailed f ->
                finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                        parentTaskId, f.toolName(), f.error(), f.durationMs(), true);
            case StreamEvent.SubAgentStarted s ->
                startSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        parentTaskId, s.taskId(), s.agentName(), s.subSessionId(), s.input());
            case StreamEvent.SubAgentDone sd ->
                finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        sd.taskId(), sd.agentName(), sd.finalText(), null);
            case StreamEvent.SubAgentError sErr ->
                finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        sErr.taskId(), sErr.agentName(), null, sErr.error());
            default -> {}
        }
    }

    // ── Live card helpers ───────────────────────────────────────────────────
    //
    // Card and list ids are keyed on the DURABLE sub-session id (resolved
    // from the ephemeral run taskId via {@code taskToSession}), so a reload
    // mid-run rebuilds the same nodes from persisted state. A card appends
    // into its parent sub-agent's nested child list when it has a parent,
    // otherwise into the top-level conversation. REPLACE (done/failed) needs
    // no target id — the card's own <li> id is morphed wherever it lives.

    /** The session-keyed nesting scope for a parent sub-agent run, or {@code null} at top level. */
    private static String scopeOf(java.util.UUID parentTaskId,
                                  java.util.Map<java.util.UUID, java.util.UUID> taskToSession) {
        if (parentTaskId == null) return null;
        java.util.UUID sid = taskToSession.get(parentTaskId);
        // Fall back to the taskId itself if the mapping is somehow missing —
        // still consistent within this live stream.
        return (sid != null ? sid : parentTaskId).toString();
    }

    private void startToolCard(ChatPage liveView,
                               java.util.LinkedHashMap<String, LiveTask> liveTasks,
                               String[] openTaskNodeId,
                               ai.mindconnect.chatui.service.StreamBus bus,
                               java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                               java.util.UUID parentTaskId, String toolName, java.util.Map<String, Object> arguments) {
        String scope = scopeOf(parentTaskId, taskToSession);
        String key  = "tool-" + (scope == null ? "top" : scope) + "-" + liveTasks.size() + "-" + toolName;
        String node = "task-" + key;
        liveTasks.put(key, new LiveTask(node, toolName, false, arguments, parentTaskId));
        openTaskNodeId[0] = null;
        var card = TaskCardComponent.runningTool(node, toolName, arguments);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishToolCard(ChatPage liveView,
                                java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                String[] openTaskNodeId,
                                ai.mindconnect.chatui.service.StreamBus bus,
                                java.util.UUID parentTaskId, String toolName,
                                String resultOrError, long durationMs, boolean failed) {
        LiveTask lt = findOpenTool(liveTasks, toolName, parentTaskId);
        if (lt == null) return;
        lt.output = resultOrError;
        lt.durationMs = durationMs;
        lt.done = true;
        var card = failed
                ? TaskCardComponent.failedTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs)
                : TaskCardComponent.doneTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs);
        publishPatch(bus, liveView.streamTaskUpdate(card));
        if (lt.nodeId.equals(openTaskNodeId[0])) openTaskNodeId[0] = null;
    }

    private void startSubAgentCard(ChatPage liveView,
                                   java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                   String[] openTaskNodeId,
                                   ai.mindconnect.chatui.service.StreamBus bus,
                                   java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                                   java.util.UUID parentTaskId, java.util.UUID taskId, String agentName,
                                   java.util.UUID subSessionId, String input) {
        // Key the card on the durable session id so a reload rebuilds the
        // identical node and continuing patches still target it.
        java.util.UUID cardKey = subSessionId != null ? subSessionId : taskId;
        if (subSessionId != null) taskToSession.put(taskId, subSessionId);
        String scope = scopeOf(parentTaskId, taskToSession);
        String node  = "task-sub-" + cardKey;
        liveTasks.put("sub-" + taskId, new LiveTask(node, agentName, true, null, parentTaskId));
        openTaskNodeId[0] = null;
        // The run_agent task message is carried on SubAgentStarted, so the
        // Input block shows immediately — like a normal tool call. The
        // open-session link uses the durable session id.
        var card = TaskCardComponent.runningSubAgent(node, agentName, cardKey.toString(),
                cardKey.toString(), input);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishSubAgentCard(ChatPage liveView,
                                    java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                    String[] openTaskNodeId,
                                    ai.mindconnect.chatui.service.StreamBus bus,
                                    java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                                    java.util.UUID taskId, String agentName,
                                    String finalText, String error) {
        LiveTask lt = liveTasks.get("sub-" + taskId);
        if (lt == null) return;
        lt.done = true;
        // Don't REPLACE the whole sub-agent card — that would morph its
        // nested child <ul> back to empty and wipe the children already
        // streamed into it. Instead flip the summary marker (visible while
        // collapsed) in place and append the answer beneath the nested tree.
        java.util.UUID cardKey = taskToSession.getOrDefault(taskId, taskId);
        String tid = cardKey.toString();
        var summary = error != null
                ? TaskCardComponent.failedSubAgentSummary(tid, agentName)
                : TaskCardComponent.doneSubAgentSummary(tid, agentName, lt.durationMs);
        var answer = TaskCardComponent.subAgentAnswer(tid, error != null ? error : finalText);
        publishPatch(bus, liveView.streamSubAgentDone(summary, TaskCardComponent.stackId(tid), answer));
        if (lt.nodeId.equals(openTaskNodeId[0])) openTaskNodeId[0] = null;
    }

    /**
     * Chooses the APPEND target for a freshly-started card: the top-level
     * conversation when {@code scope} is null, otherwise the parent
     * sub-agent's (session-keyed) nested child list.
     */
    private UiPatch appendCard(ChatPage liveView, String scope, TaskCardComponent card) {
        if (scope == null) {
            return liveView.streamTaskStart(card);
        }
        return liveView.streamTaskStartInto(TaskCardComponent.childListId(scope), card);
    }

    /**
     * Finds the live tool task that hasn't completed yet for the given tool
     * name within the given parent scope. Last matching (most recently
     * inserted) wins when several are running.
     */
    private static LiveTask findOpenTool(java.util.LinkedHashMap<String, LiveTask> live,
                                          String toolName, java.util.UUID parentTaskId) {
        LiveTask candidate = null;
        for (var e : live.entrySet()) {
            LiveTask lt = e.getValue();
            if (lt.isSubAgent || lt.done) continue;
            if (!lt.name.equals(toolName)) continue;
            if (!java.util.Objects.equals(lt.parentTaskId, parentTaskId)) continue;
            candidate = lt; // keep iterating — last matching wins (most recent)
        }
        return candidate;
    }

    /**
     * Legacy single-emitter helper, kept only for the early-return paths
     * (validation failures) that close the emitter before a StreamBus is
     * created. Inside the regular streaming path the per-channel bus is
     * the publish target — see {@link #publishPatch}.
     */
    private void sendPatch(SseEmitter emitter, UiPatch patch) {
        try {
            String json = objectMapper.writeValueAsString(patch);
            emitter.send(SseEmitter.event().name("patch").data(json));
        } catch (Exception e) {
            log.warn("Failed to send SSE patch", e);
        }
    }

    /**
     * Publishes a {@code patch} event onto the channel's multiplex bus.
     * Every attached subscriber (the original POST emitter + any
     * reconnect-GET emitters from {@code /streams/{id}/sse}) sees it; the
     * ring buffer keeps the last N for late joiners.
     */
    private String publishPatch(ai.mindconnect.chatui.service.StreamBus bus, UiPatch patch) {
        try {
            String json = objectMapper.writeValueAsString(patch);
            bus.publish("patch", json);
            return json;
        } catch (Exception e) {
            log.warn("Failed to publish SSE patch", e);
            return null;
        }
    }

    /**
     * Best-effort working-memory snapshot. Returns {@code null} on any error
     * so the UI can still render without the token bar — never lets a stats
     * failure tear down the page.
     */
    private WorkingMemory safeMemorySnapshot(UUID sessionId) {
        try {
            return chatService.memorySnapshot(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load working memory for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void logEvent(StreamEvent event, String agentName) {
        streamLogger(agentName).accept(event);
    }

    private Consumer<StreamEvent> streamLogger(String agentName) {
        return event -> {
            switch (event) {
                case StreamEvent.AskingLlm a ->
                    log.info("asking llm");
                case StreamEvent.ToolCallStarted s ->
                    log.info("tool started: {}", s.toolName());
                case StreamEvent.ToolCallResult r ->
                    log.info("tool done: {} ({}ms)", r.toolName(), r.durationMs());
                case StreamEvent.ToolCallFailed f ->
                    log.warn("tool failed: {} ({}ms): {}", f.toolName(), f.durationMs(), f.error());
                case StreamEvent.Reviewing rv ->
                    log.info("reviewing: {}", rv.reviewerName());
                case StreamEvent.ReviewerDecision rd ->
                    log.info("reviewer decision: {} → {}", rd.reviewerName(), rd.verdict());
                case StreamEvent.ResponseRevised rev ->
                    log.info("response revised: {} blocked={}", rev.reason(), rev.blocked());
                case StreamEvent.SubAgentStarted s ->
                    log.info("sub-agent started: {} (depth {})", s.agentName(), s.depth());
                case StreamEvent.SubAgentEvent se ->
                    logSubEvent(se, 1);
                case StreamEvent.SubAgentDone sd ->
                    log.info("sub-agent done: {}", sd.agentName());
                case StreamEvent.SubAgentError sErr ->
                    log.warn("sub-agent error: {}: {}", sErr.agentName(), sErr.error());
                default -> {}
            }
        };
    }

    private void logSubEvent(StreamEvent.SubAgentEvent wrapper, int depth) {
        StreamEvent inner = wrapper.inner();
        while (inner instanceof StreamEvent.SubAgentEvent next) {
            inner = next.inner();
            depth++;
        }
        String prefix = "  ".repeat(depth) + "↳ ";
        switch (inner) {
            case StreamEvent.AskingLlm a ->
                log.info("{}asking llm", prefix);
            case StreamEvent.ToolCallStarted s ->
                log.info("{}tool started: {}", prefix, s.toolName());
            case StreamEvent.ToolCallResult r ->
                log.info("{}tool done: {} ({}ms)", prefix, r.toolName(), r.durationMs());
            case StreamEvent.ToolCallFailed f ->
                log.warn("{}tool failed: {} ({}ms): {}", prefix, f.toolName(), f.durationMs(), f.error());
            case StreamEvent.SubAgentStarted s ->
                log.info("{}sub-agent: {} (depth {})", prefix, s.agentName(), s.depth());
            case StreamEvent.SubAgentDone sd ->
                log.info("{}sub-agent done: {}", prefix, sd.agentName());
            case StreamEvent.SubAgentError sErr ->
                log.warn("{}sub-agent error: {}: {}", prefix, sErr.agentName(), sErr.error());
            default -> {}
        }
    }
}
