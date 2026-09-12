package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.component.TaskCardComponent;
import ai.mindconnect.chatui.ui.page.ChatPage;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.session.InlineSessionAgent;
import ai.mindconnect.agent.runtime.domain.session.SessionAgent;
import ai.mindconnect.agent.runtime.domain.session.SessionAgentRef;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore;
import ai.mindconnect.agent.runtime.tools.attachment.ViewAttachmentTool;
import ai.mindconnect.ui.model.UiAction;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;
    /** Optional — null in setups where trace persistence is disabled. */
    private final LlmCallTraceRepository traceRepository;
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;
    private final ai.mindconnect.chatui.service.SessionStreams sessionStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    private final ToolApprovalStore approvalStore;
    /** What the embedding app adds to the chat — none in a standalone chat app. */
    private final ai.mindconnect.chatui.ui.ChatHostLinks hostLinks;
    private final ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository;
    private final ai.mindconnect.agent.tool.ToolRegistry toolRegistry;
    private final SessionAgentResolver agentResolver;
    /** The server's tree a user may pick a working directory from. */
    private final ai.mindconnect.agent.runtime.service.WorkingDirBrowser dirBrowser;

    public ChatUiController(AgentSessionService sessionService,
                             AgentChatService chatService,
                             AgentDefinitionRepository agentRepository,
                             AgentSessionRepository sessionRepository,
                             TodoListService todoListService,
                             ObjectMapper objectMapper,
                             LlmCallTraceRepository traceRepository,
                             ai.mindconnect.chatui.service.ActiveStreams activeStreams,
                             ai.mindconnect.chatui.service.SessionStreams sessionStreams,
                             ai.mindconnect.agentrest.service.SessionFileService sessionFiles,
                             ToolApprovalStore approvalStore,
                             org.springframework.beans.factory.ObjectProvider<ai.mindconnect.chatui.ui.ChatHostLinks> hostLinks,
                             ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository,
                             ai.mindconnect.agent.tool.ToolRegistry toolRegistry,
                             org.springframework.beans.factory.ObjectProvider<ai.mindconnect.agent.runtime.service.WorkingDirBrowser> dirBrowser) {
        this.dirBrowser = dirBrowser.getIfAvailable(() ->
                new ai.mindconnect.agent.runtime.service.WorkingDirBrowser(sessionService.workingDirPolicy()));
        this.sessionService = sessionService;
        this.sessionFiles = sessionFiles;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.todoListService = todoListService;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.activeStreams = activeStreams;
        this.sessionStreams = sessionStreams;
        this.approvalStore = approvalStore;
        this.hostLinks = hostLinks.getIfAvailable(() -> ai.mindconnect.chatui.ui.ChatHostLinks.NONE);
        this.llmConfigRepository = llmConfigRepository;
        this.toolRegistry = toolRegistry;
        this.agentResolver = new SessionAgentResolver(agentRepository);
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
        // Headers for the sidebar; only the chat being shown is loaded whole.
        var sessions = sessionRepository.findHeadersByUser(UserId.of(userId(user)));
        var latest = ChatLanding.pick(sessions, lastShownChat())
                .flatMap(sessionRepository::findById);
        if (latest.isEmpty()) {
            // A GET does not create anything: a prefetch, a link preview or two
            // tabs opening at once would each leave an empty chat behind. The
            // empty state offers the same button the sidebar does.
            return ResponseEntity.ok(emptyShell());
        }
        return ResponseEntity.ok(shell(latest.get(), sessions));
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
                shell(session, sessionRepository.findHeadersByUser(UserId.of(userId))));
    }

    /** The agent, the model and the prompt of this chat, as a dialog over the conversation. */
    @GetMapping("/sessions/{sessionId}/settings")
    public ResponseEntity<UiPatch> settingsDialog(@PathVariable("sessionId") String sessionIdValue,
                                                  @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var effective = agentResolver.resolve(session);
        AgentId agentId = boundAgentId(session);

        var form = new ai.mindconnect.chatui.ui.component.ChatSettingsComponent(
                sessionId, llmConfigRepository.findAll(), selectableAgents(agentId),
                effective.llmConfigName(), agentId, effective.systemPrompt()).render();

        return ResponseEntity.ok(openDialog(
                ai.mindconnect.chatui.ui.component.ChatSettingsComponent.TITLE, form));
    }

    /**
     * Applies the dialog: either an agent takes over, or the model and the
     * prompt below it do.
     *
     * <p>Tools are not on this form and are therefore never written from it.
     * They are switched in the "+" menu's pickers, and a chat that goes to
     * this dialog to change its model must not lose what it switched on there
     * — so whatever the chat carries is carried over, and only switching to a
     * DIFFERENT agent drops it, because that agent's own tools are the point
     * of switching.
     */
    @PostMapping("/sessions/{sessionId}/settings")
    public ResponseEntity<UiPage> applySettings(@PathVariable("sessionId") String sessionIdValue,
                                                @RequestBody Map<String, Object> raw,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var body = new FormBody(raw);
        String agentId = body.str("agentId");

        SessionAgent agent;
        if (agentId != null && !agentId.isBlank()) {
            var def = agentRepository.findById(AgentId.of(agentId))
                    .orElseThrow(() -> new IllegalArgumentException("No such agent: " + agentId));
            // Switching to another agent hands the chat over completely: that
            // agent's model, tools and prompt win, which is what the picker
            // promises. Staying on the same one keeps what this chat chose —
            // the ref carries exactly these overrides for that.
            boolean sameAgent = def.id().equals(boundAgentId(session));
            var previous = sameAgent ? sessionRef(session) : null;

            // Only a value that actually differs is stored: an untouched field
            // must not turn into an override that then stops tracking edits to
            // the agent itself.
            String prompt = sameAgent ? differing(body.str("systemPrompt"), def.systemPrompt()) : null;
            String llm = sameAgent ? differing(body.str("llmConfigName"), def.llmConfigName()) : null;

            agent = new SessionAgentRef(def.id(), true, def.name(), llm,
                    previous == null ? null : previous.tools(),
                    previous == null ? null : previous.toolSearch(),
                    prompt);
        } else if (session.mainAgent().orElse(null) instanceof InlineSessionAgent kept) {
            // A chat that is already its own agent: the model and the prompt
            // change, its identity and its tools do not. Rebuilt component by
            // component rather than through inlineAgent(), which mints a new
            // id — approvals and the prompt's agent metadata are keyed by it.
            agent = new InlineSessionAgent(kept.id(), kept.main(), kept.label(),
                    orKeep(body.str("systemPrompt"), kept.systemPrompt()),
                    orKeep(body.str("llmConfigName"), kept.llmConfigName()),
                    kept.tools(), kept.toolSearch(), kept.callableAgents());
        } else {
            // Detaching from a registry agent: a new agent under a new id, as
            // the switch says. It starts on what the chat was actually
            // running, so leaving the agent behind does not also silently
            // change what the chat can do.
            //
            // The tool BINDINGS are carried over whole rather than rebuilt
            // from their names, for the reason pickTools spells out: a name
            // round-trip drops every tool the registry cannot resolve on this
            // machine — Gmail without credentials — and with it whatever the
            // binding carried beyond the name. The roster comes along too:
            // dropping it would hand a chat the run of every agent the moment
            // it edited its own prompt, which is the hole SessionAgentRef's
            // javadoc warns about.
            var effective = agentResolver.resolve(session);
            agent = new InlineSessionAgent(AgentId.random(), true, "Chat",
                    orKeep(body.str("systemPrompt"), effective.systemPrompt()),
                    orKeep(body.str("llmConfigName"), effective.llmConfigName()),
                    effective.tools(), effective.toolSearchOrOff(),
                    effective.callableAgents());
        }
        var saved = sessionService.replaceSessionAgent(sessionId, agent);
        String userId = userId(user);
        return ResponseEntity.ok(
                shell(saved, sessionRepository.findHeadersByUser(UserId.of(userId))));
    }

    /** This chat's own overrides on the agent it references, or {@code null}. */
    private static SessionAgentRef sessionRef(AgentSession session) {
        return session.mainAgent()
                .filter(a -> a instanceof SessionAgentRef)
                .map(a -> (SessionAgentRef) a)
                .orElse(null);
    }

    /** The submitted value, or what the chat already had when nothing was submitted. */
    private static String orKeep(String submitted, String current) {
        return submitted == null || submitted.isBlank() ? current : submitted;
    }

    /**
     * The directory dialog: a folder chooser over the server's tree, opened
     * at the chat's working directory — at the root when it has none or
     * the directory cannot be listed any more (a directory that vanished
     * must not take the dialog down with it).
     */
    @GetMapping("/sessions/{sessionId}/dirs-dialog")
    public ResponseEntity<UiPatch> dirDialog(@PathVariable SessionId sessionId,
                                             @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        return ResponseEntity.ok(openDialog("Working directory",
                session.hasWorkingDir() ? session.workingDir() : "The server's default directory",
                pickerForm(session, session.workingDir())));
    }

    /** The chooser's form at {@code path}, or at the root when there is none or it cannot be listed. */
    private ai.mindconnect.ui.model.UiForm pickerForm(ai.mindconnect.agent.runtime.domain.AgentSession session, String path) {
        ai.mindconnect.agent.runtime.service.WorkingDirBrowser.Listing listing;
        try {
            listing = dirBrowser.list(session.userId(), path);
        } catch (RuntimeException e) {
            listing = dirBrowser.list(session.userId(), null);
        }
        return ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.form(session.id(), session, listing);
    }

    /** One step down or up in the chooser — the form alone is redrawn, at {@code path}. */
    @GetMapping("/sessions/{sessionId}/dirs")
    public ResponseEntity<UiPatch> browseDirs(@PathVariable SessionId sessionId,
                                              @RequestParam(required = false) String path,
                                              @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var listing = dirBrowser.list(sessionOpt.get().userId(), path);
        return ResponseEntity.ok(redrawnPicker(sessionOpt.get(), listing));
    }

    /** The path typed into the field, opened below it — a path that is no directory is an error toast. */
    @PostMapping("/sessions/{sessionId}/dirs/go")
    public ResponseEntity<UiPatch> goDir(@PathVariable SessionId sessionId,
                                         @RequestBody Map<String, Object> raw,
                                         @AuthenticationPrincipal OidcUser user) {
        return browseDirs(sessionId, pathOf(raw), user);
    }

    /**
     * Makes the field's path the working directory — empty for the server's
     * default — and closes the dialog. The composer redraws, since its
     * directory button names the folder.
     */
    @PostMapping("/sessions/{sessionId}/dirs/use")
    public ResponseEntity<UiPatch> useDir(@PathVariable SessionId sessionId,
                                          @RequestBody Map<String, Object> raw,
                                          @AuthenticationPrincipal OidcUser user) {
        if (ownedSession(sessionId, user).isEmpty()) return ResponseEntity.notFound().build();
        var saved = sessionService.changeWorkingDir(sessionId, pathOf(raw), null);
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(composerRefresh(saved))
                .toast(ai.mindconnect.ui.model.UiToast.success(saved.hasWorkingDir()
                        ? "Working directory: " + saved.workingDir()
                        : "Working in the server's default directory.").title("Working directory")));
    }

    /** Creates the named folder in the directory shown and steps into it — the chooser's New folder. */
    @PostMapping("/sessions/{sessionId}/dirs/create")
    public ResponseEntity<UiPatch> createDir(@PathVariable SessionId sessionId,
                                             @RequestBody Map<String, Object> raw,
                                             @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        String name = raw == null ? null
                : new FormBody(raw).str(ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.NEW_FOLDER_FIELD);
        var created = dirBrowser.create(session.userId(), pathOf(raw), name);
        return ResponseEntity.ok(redrawnPicker(session, dirBrowser.list(session.userId(), created.toString()))
                .toast(ai.mindconnect.ui.model.UiToast.success("Created " + created).title("New folder")));
    }

    /** Adds the field's path to the additional directories; the dialog stays open and shows it. */
    @PostMapping("/sessions/{sessionId}/dirs/add")
    public ResponseEntity<UiPatch> addDir(@PathVariable SessionId sessionId,
                                          @RequestBody Map<String, Object> raw,
                                          @AuthenticationPrincipal OidcUser user) {
        if (ownedSession(sessionId, user).isEmpty()) return ResponseEntity.notFound().build();
        String path = pathOf(raw);
        if (path == null) throw new IllegalArgumentException("Type or pick a directory to add first");
        var saved = sessionService.addDirectory(sessionId, path);
        return ResponseEntity.ok(redrawnPicker(saved, dirBrowser.list(saved.userId(), path))
                .toast(ai.mindconnect.ui.model.UiToast.success("Added " + path).title("Additional directories")));
    }

    /** Removes one additional directory; the dialog stays open. */
    @PostMapping("/sessions/{sessionId}/dirs/remove")
    public ResponseEntity<UiPatch> removeDir(@PathVariable SessionId sessionId,
                                             @RequestParam String path,
                                             @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var saved = sessionService.changeWorkingDir(sessionId, session.workingDir(),
                session.additionalDirs().stream().filter(d -> !d.equals(path)).toList());
        return ResponseEntity.ok(redrawnPicker(saved, dirBrowser.list(saved.userId(), saved.workingDir()))
                .toast(ai.mindconnect.ui.model.UiToast.success("Removed " + path).title("Additional directories")));
    }

    /** The chooser's form redrawn in place. */
    private UiPatch redrawnPicker(ai.mindconnect.agent.runtime.domain.AgentSession session,
                                  ai.mindconnect.agent.runtime.service.WorkingDirBrowser.Listing listing) {
        return UiPatch.of().patch(UiPatch.Operation.replace(
                ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.ID,
                ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.form(session.id(), session, listing)));
    }

    /** The chooser's path field, trimmed; {@code null} when empty. */
    private static String pathOf(Map<String, Object> raw) {
        String path = raw == null ? null
                : new FormBody(raw).str(ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.PATH_FIELD);
        return path == null || path.isBlank() ? null : path.trim();
    }

    /**
     * The composer drawn afresh from the saved session — its directory button
     * names the working directory, and the menu behind its "+" carries the
     * file and tool counts. Rebuilt whole rather than patched in pieces, so
     * the three can never disagree with each other.
     *
     * <p>It keeps the state the composer is in: a tool switched on mid-turn
     * must not swap the Stop button for a Send button, which is what a
     * hard-coded idle form used to do.
     */
    private UiPatch.Operation composerRefresh(ai.mindconnect.agent.runtime.domain.AgentSession session) {
        var agent = agentResolver.resolve(session);
        boolean streaming = activeStreams.findHandle(
                ai.mindconnect.chatui.service.SessionOwnership.channelOf(session.id())).isPresent();
        var form = new ai.mindconnect.chatui.ui.component.ChatFormComponent(
                        session.id(), agent.id(), streaming)
                .withModelLabel(agent.llmConfigName())
                .withAttachmentCount(sessionFiles.attachments(session.id()).size())
                .withToolCount(agent.tools() == null ? 0 : agent.tools().size())
                .withWorkingDir(session.workingDir())
                .withDirChoice(sessionService.workingDirChoice());
        return UiPatch.Operation.replace(form.id(), form.render());
    }

    /** The rename dialog for one chat. */
    @GetMapping("/sessions/{sessionId}/rename")
    public ResponseEntity<UiPatch> renameDialog(@PathVariable("sessionId") String sessionIdValue,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        String current = sessionOpt.get().title();

        var form = ai.mindconnect.ui.model.UiForm.of("chat-rename-" + sessionId.value(), "Rename chat")
                .field(ai.mindconnect.ui.model.UiField.text("title", "Title", current)
                        .asEditable().asRequired())
                .action(UiAction.primary("save", "Save").icon("save")
                        .onClick(trigger(on(ChatUiController.class).rename(sessionId.value(), null, null),
                                "chat-rename-" + sessionId.value())))
                .action(UiAction.secondary("cancel", "Cancel")
                        .onClick(trigger(on(ChatUiController.class).closeDialog())));

        return ResponseEntity.ok(openDialog("Rename chat", form));
    }

    /** Applies a new title and redraws — the sidebar entry changes with it. */
    @PostMapping("/sessions/{sessionId}/rename")
    public ResponseEntity<UiPage> rename(@PathVariable("sessionId") String sessionIdValue,
                                         @RequestBody Map<String, Object> raw,
                                         @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        String title = new FormBody(raw).str("title");
        if (title != null && !title.isBlank()) {
            sessionService.updateTitle(sessionId, title.trim());
        }
        String userId = userId(user);
        var sessions = sessionRepository.findHeadersByUser(UserId.of(userId));
        var current = sessionRepository.findById(sessionId).orElseThrow();
        return ResponseEntity.ok(shell(current, sessions));
    }

    /**
     * Deletes a chat and opens the next one — or a fresh chat when that was
     * the last. The conversation goes with it; there is nothing left to show.
     */
    @PostMapping("/sessions/{sessionId}/delete")
    public ResponseEntity<UiPage> deleteSession(@PathVariable("sessionId") String sessionIdValue,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        String userId = userId(user);
        if (ownedSession(sessionId, user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        sessionService.deleteSession(sessionId);
        log.info("Chat {} deleted", sessionId);

        var sessions = sessionRepository.findHeadersByUser(UserId.of(userId));
        var newest = sessions.isEmpty()
                ? java.util.Optional.<AgentSession>empty()
                : sessionRepository.findById(sessions.get(0).id());
        if (newest.isEmpty()) {
            var fresh = openDefaultChat(userId);
            return ResponseEntity.ok(shell(fresh, List.of(fresh)));
        }
        return ResponseEntity.ok(shell(newest.get(), sessions));
    }

    /**
     * The session, but only for the user it belongs to. Every endpoint that
     * addresses a session by id goes through here: the id is the only thing
     * standing between one user's chat and another's, and an id is not a
     * secret — it travels in URLs, links and logs.
     */
    private java.util.Optional<AgentSession> ownedSession(
            SessionId sessionId, OidcUser user) {
        return sessionRepository.findById(sessionId)
                .filter(session -> ai.mindconnect.chatui.service.SessionOwnership.owns(session, user));
    }

    /** Closes the settings dialog without touching anything. */
    @PostMapping("/close-dialog")
    public ResponseEntity<UiPatch> closeDialog() {
        return ResponseEntity.ok(UiPatch.of().patch(UiPatch.Operation.remove("chat-dialog")));
    }

    // ── Building the shell ──────────────────────────────────────────────────

    /** The chat app shell: history left, agent and title on top, conversation. */
    /** The browser session's note of the chat it last had on screen. */
    static final String LAST_SHOWN_CHAT = "mc.chat.lastShown";

    /**
     * The chat this browser session last had on screen, or {@code null}.
     * Kept in the HTTP session rather than on the chat: it is where this
     * browser was, not something about the conversation.
     */
    private static SessionId lastShownChat() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(LAST_SHOWN_CHAT,
                org.springframework.web.context.request.RequestAttributes.SCOPE_SESSION);
        return value instanceof String id ? SessionId.of(id) : null;
    }

    /** The browser session's record of the chats it has had on screen, and since when. */
    static final String SEEN_CHATS = "mc.chat.seen";

    /**
     * Notes the chat about to be shown — so coming back to the chat lands on
     * it again, and so it no longer counts as new — and returns what this
     * browser session has now seen, for marking the chats started elsewhere.
     */
    private static SeenChats rememberShown(SessionId sessionId) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) return SeenChats.from(java.time.Instant.now()).withShown(sessionId);
        int session = org.springframework.web.context.request.RequestAttributes.SCOPE_SESSION;
        attributes.setAttribute(LAST_SHOWN_CHAT, sessionId.value(), session);
        Object stored = attributes.getAttribute(SEEN_CHATS, session);
        SeenChats seen = (stored instanceof SeenChats known ? known : SeenChats.from(java.time.Instant.now()))
                .withShown(sessionId);
        attributes.setAttribute(SEEN_CHATS, seen, session);
        return seen;
    }

    private UiPage shell(AgentSession session,
                         List<? extends AgentSessionHeader> sessions) {
        var seen = rememberShown(session.id());
        var agent = agentResolver.resolve(session);
        var chat = buildChatPage(session, agent);
        var appShell = new ai.mindconnect.chatui.ui.component.ChatShellComponent(
                sessions, session, agent.name(), chat.renderContent(), agentIcons())
                .withActivity(runningSessions(sessions), waitingSessions(sessions))
                .withUnseen(seen.unseen(sessions))
                .render();
        var page = UiPage.of("/chat/sessions/" + session.id().value(), appShell);
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
     * <p>It runs on the seeded {@code default-chat} agent where the installation
     * has one, so its prompt, its model, its tools and the roster it may
     * delegate to are configuration like every other agent's — changed in the
     * admin UI rather than compiled in here. That is the point: the defaults
     * of the chat everyone lands in should not be the one thing you cannot
     * edit.
     *
     * <p>An installation seeded before that agent existed has no such definition,
     * and falls back to the inline agent this controller has always built. So
     * upgrading changes nothing until the agent is installed.
     */
    /**
     * A dialog value the runtime refuses — a working directory that does
     * not exist, an agent id nobody has — comes back as an error toast over
     * the dialog that is still open, not as the client's bare "HTTP 400".
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<UiPatch> rejected(IllegalArgumentException e) {
        return ResponseEntity.ok(UiPatch.of().toast(
                ai.mindconnect.ui.model.UiToast.error(e.getMessage() == null ? "Invalid value" : e.getMessage())
                        .title("Not applied")));
    }

    private AgentSession openDefaultChat(String userId) {
        return agentRepository.findByName(DEFAULT_CHAT_AGENT)
                .map(a -> sessionService.openChat(a.id(), UserId.of(userId)))
                .orElseGet(() -> sessionService.openChat(inlineDefaultChatAgent(), UserId.of(userId)));
    }

    /**
     * What a chat can do before anyone switches anything on. It used to live
     * on the settings component, next to the tool multiselect that read it;
     * the multiselect is gone (tools are switched in the "+" menu now) and
     * the default belongs to whoever opens a chat.
     */
    private static final List<String> DEFAULT_TOOLS = List.of(
            "list_agents", "run_agent", "run_agents",
            "todo_read", "todo_write");

    /** The fallback chat agent: the standard model, the standard tools. */
    private InlineSessionAgent inlineDefaultChatAgent() {
        return inlineAgent(defaultLlmConfigName(), DEFAULT_TOOLS, true);
    }

    /** The session's own agent, built from a model name and tool names. */
    private InlineSessionAgent inlineAgent(
            String llmConfigName, List<String> tools, boolean toolSearch) {
        return inlineAgent(llmConfigName, tools, toolSearch, null);
    }

    /** @param systemPrompt {@code null} or blank falls back to the built-in one. */
    private InlineSessionAgent inlineAgent(
            String llmConfigName, List<String> tools, boolean toolSearch, String systemPrompt) {
        List<String> names = tools == null || tools.isEmpty() ? DEFAULT_TOOLS : tools;
        var known = allToolNames();
        return InlineSessionAgent.of(
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
            needs one — `list_agents` shows which exist. Use `todo_write` to
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
            AgentDefinition def, List<String> names) {
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
                    : ai.mindconnect.agent.tool.AgentTool.of(n));
        }
        return List.copyOf(picked.values());
    }
    /**
     * The registry agent this chat runs on, or {@code null} for a chat with an
     * agent of its own.
     *
     * <p>Two shapes mean the same thing. Picking an agent in the settings
     * dialog writes a {@link SessionAgentRef};
     * opening a chat from an agent — which is now every chat, via
     * {@code default-chat} — sets only {@code agentDefinitionId} and leaves the
     * session-agent list empty. Reading just the ref reported "no agent" for
     * the second kind, and pressing Apply on one detached the chat from the
     * agent it was plainly running on.
     *
     * <p>The id is checked against the registry: an inline agent's id is minted
     * for the session and would otherwise look like a binding.
     */
    private AgentId boundAgentId(AgentSession session) {
        AgentId ref = session.mainAgent()
                .filter(a -> a instanceof SessionAgentRef)
                .map(SessionAgent::id)
                .orElse(null);
        if (ref != null) {
            return ref;
        }
        AgentId fromSession = session.agentDefinitionId();
        return fromSession != null && agentRepository.findById(fromSession).isPresent()
                ? fromSession : null;
    }

    /**
     * Icon name per agent-definition id, for the history drawer. Read from the
     * registry in one go: a row only needs the icon, and resolving every
     * session's agent separately would be one lookup per conversation.
     */
    private java.util.Map<AgentId, String> agentIcons() {
        var icons = new java.util.HashMap<AgentId, String>();
        for (AgentDefinition a : agentRepository.findAll()) {
            icons.put(a.id(), a.iconOrDefault());
        }
        return icons;
    }

    private List<AgentDefinition> selectableAgents(AgentId currentAgentId) {
        return agentRepository.findAll().stream()
                .filter(a -> a.status() != AgentDefinitionStatus.DEPRECATED)
                .filter(a -> CHAT_GROUP.equals(a.groupOrDefault()) || a.id().equals(currentAgentId))
                .toList();
    }

    /** The one rubric whose agents a person opens a chat with. */
    private static final String CHAT_GROUP = "assistants";

    /** Everything a chat can be given: the registry plus the runtime's own two. */
    private List<String> allToolNames() {
        var names = new java.util.TreeSet<String>();
        toolRegistry.toolNamesByGroup().values().forEach(names::addAll);
        names.add(InlineAgentTools.RUN_AGENT);
        names.add(InlineAgentTools.RUN_AGENTS);
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
        return ai.mindconnect.chatui.service.SessionOwnership.userIdOf(user);
    }

    @PostMapping("/agents/{agentId}/sessions")
    public ResponseEntity<UiPage> startSession(@PathVariable("agentId") String agentIdValue,
                                               @AuthenticationPrincipal OidcUser user) {
        AgentId agentId = AgentId.of(agentIdValue);
        String userId = user.getPreferredUsername();
        return agentRepository.findById(agentId)
                .map(agent -> {
                    var session = sessionService.openChat(agentId, UserId.of(userId));
                    return ResponseEntity.ok(shell(session,
                            sessionRepository.findHeadersByUser(UserId.of(userId))));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** {@code kind=images} narrows the attach dialog to pictures. */
    public static final String ATTACH_IMAGES = "images";
    /** {@code kind=files} — the attach dialog as it always was, anything goes. */
    public static final String ATTACH_FILES = "files";

    @GetMapping("/sessions/{sessionId}/attach-dialog")
    public ResponseEntity<UiPatch> attachDialog(@PathVariable("sessionId") String sessionIdValue,
                                                @RequestParam(defaultValue = ATTACH_FILES) String kind,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (ownedSession(sessionId, user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean imagesOnly = ATTACH_IMAGES.equals(kind);
        // The dialog carries both halves: what is already attached, and the
        // drop zone to add more. Uploads patch the list in place, so it stays
        // open and current while files arrive.
        //
        // Pictures get their own entry in the "+" menu because that is what
        // people go looking for, but not their own dialog: the same list and
        // the same endpoint, with the file chooser narrowed to images. A
        // second dialog would have shown a second, disagreeing copy of what
        // is attached.
        var body = ai.mindconnect.ui.model.UiStack.of("chat-attach-body");
        body.gap(12);
        body.child(ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent
                .node(sessionId, sessionFiles.attachments(sessionId), sessionFiles.listAttachments(sessionId)));
        body.child(ai.mindconnect.chatui.ui.page.ChatPage.attachZone(sessionId, imagesOnly));
        return ResponseEntity.ok(openDialog(imagesOnly ? "Add images" : "Attached files", body));
    }

    // ── The "+" menu's pickers ─────────────────────────────────────────

    /** Every tool the registry can hand out, with this chat's switched on. */
    @GetMapping("/sessions/{sessionId}/tools-dialog")
    public ResponseEntity<UiPatch> toolsDialog(@PathVariable("sessionId") String sessionIdValue,
                                               @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(openDialog("Tools", toolsPicker(sessionOpt.get())));
    }

    /** The picker's body for the session as it stands right now. */
    private ai.mindconnect.ui.model.UiNode toolsPicker(AgentSession session) {
        var effective = agentResolver.resolve(session);
        var byGroup = new java.util.TreeMap<String, java.util.Set<String>>(toolRegistry.toolNamesByGroup());
        // The runtime's own two have no factory and therefore no group; they
        // are agent functions, so they join the rubric the registry files
        // list_agents under.
        byGroup.merge("agents",
                new java.util.TreeSet<>(List.of(InlineAgentTools.RUN_AGENT, InlineAgentTools.RUN_AGENTS)),
                (a, b) -> {
                    var merged = new java.util.TreeSet<>(a);
                    merged.addAll(b);
                    return merged;
                });
        var subgroups = new java.util.HashMap<String, String>();
        byGroup.values().forEach(names -> names.forEach(name -> {
            String subgroup = toolRegistry.subgroupOf(name);
            if (subgroup != null && !subgroup.isBlank()) subgroups.put(name, subgroup);
        }));
        var active = effective.tools().stream()
                .map(ai.mindconnect.agent.tool.AgentTool::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.node(
                session.id(), byGroup, active, subgroups, effective.toolSearchOrOff().enabled());
    }

    /** One tool on or off. The picker stays open and says what changed. */
    @PostMapping("/sessions/{sessionId}/tools")
    public ResponseEntity<UiPatch> toggleTool(@PathVariable("sessionId") String sessionIdValue,
                                              @RequestParam String tool,
                                              @RequestParam boolean on,
                                              @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = agentResolver.resolve(sessionOpt.get());
        var chosen = new java.util.LinkedHashSet<String>(effective.tools().stream()
                .map(ai.mindconnect.agent.tool.AgentTool::name).toList());
        if (on) chosen.add(tool); else chosen.remove(tool);
        var saved = replaceTools(sessionOpt.get(), List.copyOf(chosen),
                effective.toolSearchOrOff().enabled());
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.BODY_ID,
                toolsPicker(saved)));
    }

    /** Tool search on or off — whether the chat may find what is switched off. */
    @PostMapping("/sessions/{sessionId}/tool-search")
    public ResponseEntity<UiPatch> toggleToolSearch(@PathVariable("sessionId") String sessionIdValue,
                                                    @RequestParam boolean on,
                                                    @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = agentResolver.resolve(sessionOpt.get());
        var saved = replaceTools(sessionOpt.get(),
                effective.tools().stream().map(ai.mindconnect.agent.tool.AgentTool::name).toList(), on);
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.BODY_ID,
                toolsPicker(saved)));
    }

    /** The specialists this chat may hand work to, and the switch that lets it. */
    @GetMapping("/sessions/{sessionId}/subagents-dialog")
    public ResponseEntity<UiPatch> subAgentsDialog(@PathVariable("sessionId") String sessionIdValue,
                                                   @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(openDialog("Sub-agents", subAgentsPicker(sessionOpt.get())));
    }

    /** The sub-agent picker's body for the session as it stands right now. */
    private ai.mindconnect.ui.model.UiNode subAgentsPicker(AgentSession session) {
        var effective = agentResolver.resolve(session);
        var roster = effective.callableAgents();
        return ai.mindconnect.chatui.ui.component.ChatSubAgentsComponent.node(
                session.id(), delegatableAgents(effective), delegates(effective),
                roster != null && !roster.isEmpty());
    }

    /**
     * The tools a chat needs to delegate at all. {@code list_agents} comes
     * with them: a roster it cannot read is a roster it will guess at.
     */
    private static final List<String> DELEGATION_TOOLS =
            List.of(InlineAgentTools.RUN_AGENT, InlineAgentTools.RUN_AGENTS, "list_agents");

    /** Can this chat call another agent at all? {@code run_agent} is the answer. */
    private static boolean delegates(AgentDefinition effective) {
        return effective.tools().stream()
                .anyMatch(t -> InlineAgentTools.RUN_AGENT.equals(t.name()));
    }

    /**
     * Delegation on or off, as one switch over {@link #DELEGATION_TOOLS} —
     * three separate rows in the tools picker would make "can this chat
     * delegate?" a question with eight answers.
     */
    @PostMapping("/sessions/{sessionId}/delegation")
    public ResponseEntity<UiPatch> toggleDelegation(@PathVariable("sessionId") String sessionIdValue,
                                                    @RequestParam boolean on,
                                                    @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = agentResolver.resolve(sessionOpt.get());
        var chosen = new java.util.LinkedHashSet<String>(effective.tools().stream()
                .map(ai.mindconnect.agent.tool.AgentTool::name).toList());
        if (on) {
            // Only what this installation actually has: run_agent and
            // run_agents are the runtime's own, list_agents needs the registry
            // to offer it.
            DELEGATION_TOOLS.stream().filter(allToolNames()::contains).forEach(chosen::add);
        } else {
            DELEGATION_TOOLS.forEach(chosen::remove);
        }
        var saved = replaceTools(sessionOpt.get(), List.copyOf(chosen),
                effective.toolSearchOrOff().enabled());
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatSubAgentsComponent.BODY_ID,
                subAgentsPicker(saved)));
    }

    /**
     * "Ask" on a sub-agent: the picker closes and the composer holds the first
     * half of the brief. The chat is not reconfigured — a sub-agent needs a
     * self-contained task, and only the person typing has it.
     */
    @PostMapping("/sessions/{sessionId}/delegate")
    public ResponseEntity<UiPatch> delegateToAgent(@PathVariable("sessionId") String sessionIdValue,
                                                   @RequestParam String agent,
                                                   @RequestBody(required = false) Map<String, Object> raw,
                                                   @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        // Whatever is already in the composer keeps its place in front: the
        // brief is appended, never pasted over half a typed sentence.
        String typed = raw == null ? null : new FormBody(raw).str("message");
        String brief = "Use the " + agent + " sub-agent to ";
        String filled = typed == null || typed.isBlank() ? brief : typed.strip() + "\n\n" + brief;
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(UiPatch.Operation.replace("message",
                        ai.mindconnect.chatui.ui.component.ChatFormComponent.messageField(filled))));
    }

    /**
     * The agents this chat may hand work to: its agent's roster when it was
     * given one, otherwise everything registered that is not a utility and
     * not the chat's own agent. Deprecated agents are never offered — a
     * picker is a list of things you can pick.
     */
    private List<AgentDefinition> delegatableAgents(AgentDefinition effective) {
        var roster = effective.callableAgents();
        boolean restricted = roster != null && !roster.isEmpty();
        return agentRepository.findAll().stream()
                .filter(a -> a.status() != AgentDefinitionStatus.DEPRECATED)
                .filter(a -> !a.id().equals(effective.id()))
                .filter(a -> restricted
                        ? roster.contains(a.name())
                        : !UTILITY_GROUP.equals(a.groupOrDefault()))
                .toList();
    }

    /** The rubric of the agents the runtime calls on its own — not delegation targets. */
    private static final String UTILITY_GROUP = "utilities";

    /**
     * What every tool change answers with: the picker redrawn from the saved
     * session, and the composer too — its "+" carries the tool count, which
     * would otherwise keep stating what was true before the click.
     */
    private UiPatch afterToolChange(AgentSession saved, String bodyId,
                                    ai.mindconnect.ui.model.UiNode body) {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(bodyId, body))
                .patch(composerRefresh(saved));
    }

    /**
     * This chat's tool selection, replaced — the one thing the tools picker
     * changes. Everything else the chat chose (its model, its prompt, the
     * agent behind it) is carried over untouched, which is why this cannot
     * just call the settings dialog's handler: that one also decides who the
     * chat's agent is.
     */
    private AgentSession replaceTools(AgentSession session, List<String> chosen, boolean toolSearch) {
        var effective = agentResolver.resolve(session);
        AgentId bound = boundAgentId(session);
        SessionAgent agent;
        if (bound != null) {
            var def = agentRepository.findById(bound)
                    .orElseThrow(() -> new IllegalArgumentException("No such agent: " + bound));
            var previous = session.mainAgent()
                    .filter(a -> a instanceof SessionAgentRef)
                    .map(a -> (SessionAgentRef) a)
                    .orElse(null);
            // Only a value that actually differs becomes an override — the
            // same rule the settings dialog follows. Toggling a tool on and
            // straight back off must leave the chat tracking its agent's tool
            // list, not frozen on a copy of it.
            var offerable = def.tools().stream()
                    .map(ai.mindconnect.agent.tool.AgentTool::name)
                    .filter(allToolNames()::contains)
                    .collect(java.util.stream.Collectors.toSet());
            // pickTools against the EFFECTIVE definition, not the registry's:
            // it is the one that already carries this chat's overrides, so a
            // binding the chat chose earlier (an approval flag, a mount dir)
            // survives the next click.
            List<ai.mindconnect.agent.tool.AgentTool> toolOverride =
                    new java.util.HashSet<>(chosen).equals(offerable) ? null : pickTools(effective, chosen);
            AgentDefinition.ToolSearchConfig searchOverride =
                    toolSearch == def.toolSearchOrOff().enabled()
                            ? null
                            : new AgentDefinition.ToolSearchConfig(toolSearch, def.toolSearchOrOff().groups());
            agent = new SessionAgentRef(def.id(), true, def.name(),
                    previous == null ? null : previous.llmConfigName(),
                    toolOverride, searchOverride,
                    previous == null ? null : previous.systemPrompt());
        } else {
            agent = session.mainAgent()
                    .filter(a -> a instanceof InlineSessionAgent)
                    .map(a -> ((InlineSessionAgent) a).withTools(chosen, toolSearch))
                    .map(a -> (SessionAgent) a)
                    .orElseGet(() -> inlineAgent(effective.llmConfigName(), chosen, toolSearch,
                            effective.systemPrompt()));
        }
        return sessionService.replaceSessionAgent(session.id(), agent);
    }

    /**
     * A dialog over the untouched conversation. One dialog id, so opening a
     * second picker replaces the first instead of stacking two modals nobody
     * can close in order.
     */
    private static UiPatch openDialog(String title, ai.mindconnect.ui.model.UiNode body) {
        return openDialog(title, null, body);
    }

    /** @param closeHref where closing the dialog navigates in SSR mode; null stays put */
    private static UiPatch openDialog(String title, String closeHref,
                                      ai.mindconnect.ui.model.UiNode body) {
        var dlg = ai.mindconnect.ui.model.UiDialog.of(title, closeHref, body);
        dlg.setId("chat-dialog");
        return UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<UiPage> getSession(@PathVariable("sessionId") String sessionIdValue,
                                             @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        return ownedSession(sessionId, user)
                .map(session -> ResponseEntity.ok(shell(session,
                        sessionRepository.findHeadersByUser(UserId.of(userId(user))))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Loads the current history + memory snapshot for the session and
     * wraps them in a {@link ChatPage} bound to the given agent. All
     * chat-page rendering and patch generation goes through this single
     * factory so the controller never reaches for the model directly.
     */
    private ChatPage buildChatPage(AgentSession session,
                                   AgentDefinition agent) {
        var history = sessionService.loadHistory(session.id());
        var memory  = safeMemorySnapshot(session.id());
        // Source-of-truth for "is this turn currently streaming?" is the
        // registry — not page-local state. Lets a navigate-back during a
        // live turn render the form in Stop-mode without any client-side
        // reconciliation.
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(session.id());
        var handleOpt = activeStreams.findHandle(channelId);
        var page = new ChatPage(session, agent, history, memory, handleOpt.isPresent(),
                (toolCallId, running, in, out) ->
                        buildSubAgentCards(session.id(), toolCallId, running, in, out))
                .withBubbledApprovals(bubbledApprovalCards(session.id()))
                .withHostLinks(hostLinks);
        page.withDirChoice(sessionService.workingDirChoice());
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
                        "/chat/sessions/" + session.id().value(),
                        returnLabel)));
        return page;
    }

    /** The sessions with a turn in flight — the stream registry is the truth, as for the Stop button. */
    private java.util.Set<SessionId> runningSessions(List<? extends AgentSessionHeader> sessions) {
        java.util.Set<SessionId> running = new java.util.HashSet<>();
        for (var s : sessions) {
            if (activeStreams.findHandle(ai.mindconnect.chatui.service.SessionOwnership.channelOf(s.id())).isPresent()) running.add(s.id());
        }
        return running;
    }

    /** The sessions with a tool stopped at the approval gate — the store is the truth, as for the cards. */
    private java.util.Set<SessionId> waitingSessions(List<? extends AgentSessionHeader> sessions) {
        java.util.Set<SessionId> waiting = new java.util.HashSet<>();
        for (var s : sessions) {
            if (!approvalStore.openForRoot(s.id()).isEmpty()) waiting.add(s.id());
        }
        return waiting;
    }

    /**
     * The cards for this session's OPEN sub-agent approval questions — read
     * from the ToolApprovalStore, the single truth for bubbled requests
     * (entry exists = card shows; answered/cancelled/deleted = entry gone).
     */
    private List<ai.mindconnect.ui.model.UiList.Item> bubbledApprovalCards(SessionId sessionId) {
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
    private List<TaskCardComponent> buildSubAgentCards(SessionId parentSessionId, String toolCallId,
                                                       boolean running, String inputJson, String resultText) {
        if (toolCallId == null || toolCallId.isBlank()) return List.of();
        List<AgentSession> children;
        try {
            children = sessionRepository.findByParentSession(parentSessionId).stream()
                    .filter(s -> toolCallId.equals(s.parentToolCallId()))
                    .sorted(java.util.Comparator.comparing(
                            AgentSession::startedAt))
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

                var childList = TaskCardComponent.subAgentChildList(child.id().value());
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
                String nodeId = "task-sub-" + child.id().value();
                cards.add(TaskCardComponent.historicSubAgent(
                        nodeId, agentName, child.id().value(),
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
    private static long durationOf(AgentSession s) {
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
    public ResponseEntity<UiPatch> chat(@PathVariable("sessionId") String sessionIdValue,
                                        @RequestBody Map<String, Object> raw,
                                        @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
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
     * was signalled, 404 if no chat is currently running or the session is
     * not the caller's. The stream completes via the normal {@code Done} flow
     * once the loop reaches its next cancel-check point.
     */
    @DeleteMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<Void> cancelChat(@PathVariable("sessionId") String sessionIdValue,
                                           @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (ownedSession(sessionId, user).isEmpty()) return ResponseEntity.notFound().build();
        boolean cancelled = chatService.cancelChat(sessionId);
        log.info("DELETE /chat/api/sessions/{}/chat → cancelled={}", sessionId.value(), cancelled);
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
    public ResponseEntity<UiPatch> deleteMessages(@PathVariable("sessionId") String sessionIdValue,
                                                   @RequestParam int fromSeq,
                                                   @RequestParam int toSeq,
                                                   @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        log.info("DELETE /chat/api/sessions/{}/messages fromSeq={} toSeq={}", sessionId.value(), fromSeq, toSeq);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();

        sessionService.deleteMessages(sessionId, fromSeq, toSeq);

        return ResponseEntity.ok(
                buildChatPage(sessionOpt.get(), agentOpt.get()).headerOnly());
    }

    @PostMapping("/sessions/{sessionId}/chat/stream")
    public ResponseEntity<ai.mindconnect.ui.model.UiPatch> chatStream(@PathVariable("sessionId") String sessionIdValue,
                                                 @RequestBody Map<String, Object> raw,
                                                 @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var body = new FormBody(raw);
        String text = body.str("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
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
    public ResponseEntity<UiPatch> approvalAnswered(@PathVariable("sessionId") String sessionIdValue,
                                                    @RequestParam String callId,
                                                    @RequestParam boolean approved,
                                                    @RequestParam(defaultValue = "once") String scope,
                                                    @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        boolean delivered = chatService.answerApproval(sessionId, callId, approved,
                ApprovalScope.fromParam(scope));
        log.info("POST /chat/api/sessions/{}/approval call={} approved={} scope={} delivered={}",
                sessionId.value(), callId, approved, scope, delivered);
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
    public ResponseEntity<ai.mindconnect.ui.model.UiPatch> regenerate(@PathVariable("sessionId") String sessionIdValue,
                                                 @PathVariable int seq,
                                                 @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var agentOpt = java.util.Optional.of(agentResolver.resolve(sessionOpt.get()));
        if (agentOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Capture the user message at `seq` before we delete it — its parts,
        // so an image sent with it is sent again.
        java.util.List<ai.mindconnect.message.domain.ContentPart> parts = sessionService.loadHistory(sessionId).stream()
                .filter(m -> m.sequenceNum() == seq)
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER)
                // a message the runtime inserted mid-turn is not a question to ask again
                .filter(m -> !ViewAttachmentTool.insertedBy(m))
                .map(Message::partsOrText)
                .findFirst()
                .orElse(null);
        String text = parts == null ? null : ai.mindconnect.message.domain.ContentPart.textOf(parts);
        if (parts == null || (text.isBlank() && parts.size() == 1)) {
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
        var session = sessionOpt.get();
        return runTurnStream(session, agentOpt.get(), text, true,
                handler -> chatService.submitChat(session.id(), parts, handler));
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
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runChatStream(AgentSession session,
                                                                          AgentDefinition agent,
                                                                          String text, boolean initialRefresh) {
        return runTurnStream(session, agent, text, initialRefresh,
                handler -> chatService.submitChat(session.id(), text, handler));
    }

    /**
     * The streaming core, parameterised over WHAT starts the turn: a typed
     * message ({@code text} echoed as a user bubble) or an approval answer
     * ({@code text == null} — the card click is the input, nothing to echo).
     */
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runTurnStream(AgentSession session,
                                                                          AgentDefinition agent,
                                                                          String text, boolean initialRefresh,
                                                                          java.util.function.Function<java.util.function.Consumer<StreamEvent>, ChatTurnHandle> turnStarter) {
        SessionId sessionId = session.id();
        // Channel id == the id of the message-list container the patches
        // target. This way the client's {@code findStreamTarget} lookup
        // naturally detects "chat page mounted", and both the submitter and
        // any observer resolve the same stream.
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId);
        String returnHref = "/chat/sessions/" + sessionId.value();
        String streamLabel = agent.name() != null ? agent.name() : "Agent";
        String pendingId  = "bot-pending-"  + sessionId.value();
        String thinkingId = "bot-thinking-" + sessionId.value();

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
        java.util.Map<java.util.UUID, SessionId> taskToSession = new java.util.concurrent.ConcurrentHashMap<>();
        // Why a reviewer rewrote or blocked the answer, keyed by reviewer.
        // ResponseRevised arrives before the decision it explains.
        java.util.Map<String, String> reviewerDetail = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<ai.mindconnect.chatui.ui.component.TaskCardComponent> reviewerVerdicts =
                new java.util.concurrent.CopyOnWriteArrayList<>();

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
                // Reviewers run AFTER the answer is already on screen. Without
                // a card the chat just sits there, which is what made a
                // finished-looking turn feel stuck.
                case StreamEvent.Reviewing rv -> {
                    String node = reviewerNodeId(sessionId, rv.reviewerName());
                    publishPatch(bus, appendCard(liveView, null,
                            ai.mindconnect.chatui.ui.component.TaskCardComponent
                                    .runningReviewer(node, rv.reviewerName())));
                    openTaskNodeId[0] = node;
                }
                case StreamEvent.ReviewerDecision rd -> {
                    var verdictCard = ai.mindconnect.chatui.ui.component.TaskCardComponent.doneReviewer(
                            reviewerNodeId(sessionId, rd.reviewerName()),
                            rd.reviewerName(), String.valueOf(rd.verdict()),
                            reviewerDetail.get(LAST_REVISION));
                    publishPatch(bus, liveView.streamTaskUpdate(verdictCard));
                    // Kept for after the turn: streamDone rebuilds the list
                    // from persisted history, and a reviewer run is not part
                    // of it, so the verdict would vanish the moment it
                    // arrived. Re-appending is not persistence — a reload
                    // still loses it — but the reader gets to see it.
                    reviewerVerdicts.add(verdictCard);
                }
                // A reviewer that rewrote or blocked the answer says why; the
                // verdict card is where that belongs, so keep it for the
                // decision event that follows.
                case StreamEvent.ResponseRevised rev ->
                    reviewerDetail.put(LAST_REVISION, rev.reason());
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
                try {
                    // "done" says the TURN is over, not that it succeeded. A
                    // failed turn is over too, and a subscriber that never
                    // hears so stays in streaming state for good — the stream
                    // outlives the turn now, so nothing else ends it.
                    bus.publish("done", "");
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
                // After the rebuild, or they would be wiped by it. They sit
                // below the answer, which is also when they ran.
                for (var verdict : reviewerVerdicts) {
                    publishPatch(bus, finalView.streamTaskStart(verdict));
                }

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
                                      java.util.Map<java.util.UUID, SessionId> taskToSession) {
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
                                  java.util.Map<java.util.UUID, SessionId> taskToSession) {
        if (parentTaskId == null) return null;
        SessionId sid = taskToSession.get(parentTaskId);
        // Fall back to the taskId itself if the mapping is somehow missing —
        // still consistent within this live stream.
        return sid != null ? sid.value() : parentTaskId.toString();
    }

    /** Marker key under which a pending revision reason is parked. */
    private static final String LAST_REVISION = "__revision__";

    /**
     * One node per reviewer and session, so the running card and the verdict
     * card are the same node — the verdict REPLACEs the "reviewing…" header
     * instead of appending a second entry.
     */
    private static String reviewerNodeId(SessionId sessionId, String reviewerName) {
        return "task-review-" + sessionId.value() + "-" + reviewerName.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private void startToolCard(ChatPage liveView,
                               java.util.LinkedHashMap<String, LiveTask> liveTasks,
                               String[] openTaskNodeId,
                               ai.mindconnect.chatui.service.StreamBus bus,
                               java.util.Map<java.util.UUID, SessionId> taskToSession,
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
                                   java.util.Map<java.util.UUID, SessionId> taskToSession,
                                   java.util.UUID parentTaskId, java.util.UUID taskId, String agentName,
                                   SessionId subSessionId, String input) {
        // Key the card on the durable session id so a reload rebuilds the
        // identical node and continuing patches still target it.
        String cardKey = subSessionId != null ? subSessionId.value() : taskId.toString();
        if (subSessionId != null) taskToSession.put(taskId, subSessionId);
        String scope = scopeOf(parentTaskId, taskToSession);
        String node  = "task-sub-" + cardKey;
        liveTasks.put("sub-" + taskId, new LiveTask(node, agentName, true, null, parentTaskId));
        openTaskNodeId[0] = null;
        // The run_agent task message is carried on SubAgentStarted, so the
        // Input block shows immediately — like a normal tool call. The
        // open-session link uses the durable session id.
        var card = TaskCardComponent.runningSubAgent(node, agentName, cardKey,
                cardKey, input);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishSubAgentCard(ChatPage liveView,
                                    java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                    String[] openTaskNodeId,
                                    ai.mindconnect.chatui.service.StreamBus bus,
                                    java.util.Map<java.util.UUID, SessionId> taskToSession,
                                    java.util.UUID taskId, String agentName,
                                    String finalText, String error) {
        LiveTask lt = liveTasks.get("sub-" + taskId);
        if (lt == null) return;
        lt.done = true;
        // Don't REPLACE the whole sub-agent card — that would morph its
        // nested child <ul> back to empty and wipe the children already
        // streamed into it. Instead flip the summary marker (visible while
        // collapsed) in place and append the answer beneath the nested tree.
        SessionId subSession = taskToSession.get(taskId);
        String tid = subSession != null ? subSession.value() : taskId.toString();
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
    private WorkingMemory safeMemorySnapshot(SessionId sessionId) {
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
