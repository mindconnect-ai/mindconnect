package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.loop.domain.TurnInterruptionEvent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.chatui.ui.component.ChatAgent;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.chatui.ui.component.TaskCardComponent;
import ai.mindconnect.chatui.ui.page.ChatPage;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.common.LoggingContext;
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

import ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.ToolState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@RestController
@RequestMapping("/chat/api")
public class ChatUiController {

    private static final Logger log = LoggerFactory.getLogger(ChatUiController.class);

    /** Whether this installation lets a user choose a chat's directories. */
    private final ai.mindconnect.agent.management.directory.WorkingDirPolicy workingDirPolicy;
    /** The chat on the runtime on the plugin kernel, behind the calls this page has always made. */
    private final ai.mindconnect.agentrest.service.SessionTurns sessionTurns;
    private final ai.mindconnect.agent.runtime.api.port.in.SessionService runtimeSessions;
    private final ai.mindconnect.agentrest.service.ChatOpening chatOpener;
    private final AgentManagement agents;
    /** What is done with sessions beside running turns in them: where the list of chats comes from. */
    private final SessionManagement sessionManagement;
    /** Asked for what the chat shows of the runtime beyond sessions and turns: how full the context is. */
    private final ai.mindconnect.agent.sdk.AgentRuntime runtime;
    private final TodoListService todoListService;
    private final ObjectMapper objectMapper;
    /** Optional — null in setups where trace persistence is disabled. */
    private final LlmCallTraceRepository traceRepository;
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;
    private final ai.mindconnect.chatui.service.SessionStreams sessionStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    /** What the embedding app adds to the chat — none in a standalone chat app. */
    private final ai.mindconnect.chatui.ui.ChatHostLinks hostLinks;
    private final ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository;
    private final ai.mindconnect.agent.tool.ToolRegistry toolRegistry;
    /** The server's tree a user may pick a working directory from. */
    private final ai.mindconnect.agent.management.directory.WorkingDirBrowser dirBrowser;

    public ChatUiController(ai.mindconnect.agent.management.directory.WorkingDirPolicy workingDirPolicy,
                             ai.mindconnect.agentrest.service.SessionTurns sessionTurns,
                             ai.mindconnect.agent.sdk.AgentRuntime runtime,
                             ai.mindconnect.agentrest.service.ChatOpening chatOpener,
                             SessionManagement sessionManagement,
                             TodoListService todoListService,
                             ObjectMapper objectMapper,
                             LlmCallTraceRepository traceRepository,
                             ai.mindconnect.chatui.service.ActiveStreams activeStreams,
                             ai.mindconnect.chatui.service.SessionStreams sessionStreams,
                             ai.mindconnect.agentrest.service.SessionFileService sessionFiles,
                             org.springframework.beans.factory.ObjectProvider<ai.mindconnect.chatui.ui.ChatHostLinks> hostLinks,
                             ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository,
                             ai.mindconnect.agent.tool.ToolRegistry toolRegistry,
                             org.springframework.beans.factory.ObjectProvider<ai.mindconnect.agent.management.directory.WorkingDirBrowser> dirBrowser) {
        this.dirBrowser = dirBrowser.getIfAvailable(() ->
                new ai.mindconnect.agent.management.directory.WorkingDirBrowser(workingDirPolicy));
        this.workingDirPolicy = workingDirPolicy;
        this.sessionFiles = sessionFiles;
        this.sessionTurns = sessionTurns;
        this.runtime = runtime;
        this.runtimeSessions = runtime.sessions();
        this.chatOpener = chatOpener;
        this.agents = runtime.service(AgentManagement.class);
        this.sessionManagement = sessionManagement;
        this.todoListService = todoListService;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.activeStreams = activeStreams;
        this.sessionStreams = sessionStreams;
        this.hostLinks = hostLinks.getIfAvailable(() -> ai.mindconnect.chatui.ui.ChatHostLinks.NONE);
        this.llmConfigRepository = llmConfigRepository;
        this.toolRegistry = toolRegistry;
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
        var sessions = chats(userId(user));
        var latest = ChatLanding.pick(sessions, lastShownChat(), this::written)
                .flatMap(id -> sessionManagement.find(id));
        if (latest.isEmpty()) {
            // A GET does not create anything: a prefetch, a link preview or two
            // tabs opening at once would each leave an empty chat behind. The
            // empty state offers the same button the sidebar does.
            return ResponseEntity.ok(emptyShell());
        }
        return ResponseEntity.ok(shell(latest.get(), sessions));
    }

    /** Whether somebody wrote in {@code chat}; a store that cannot say counts it as written. */
    private boolean written(ManagedSession chat) {
        try {
            return !sessionManagement.messages(chat.id()).isEmpty();
        } catch (RuntimeException e) {
            log.debug("Could not tell whether chat {} is empty: {}", chat.id(), e.toString());
            return true;
        }
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
        log.info("New chat {}", SessionOwnership.idOf(session));
        return ResponseEntity.ok(
                shell(session, chats(userId)));
    }

    /** The agent, the model and the prompt of this chat, as a dialog over the conversation. */
    @GetMapping("/sessions/{sessionId}/settings")
    public ResponseEntity<UiPatch> settingsDialog(@PathVariable("sessionId") String sessionIdValue,
                                                  @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var effective = effective(session);
        AgentId agentId = boundAgentId(session);

        var form = new ai.mindconnect.chatui.ui.component.ChatSettingsComponent(
                sessionId, llmConfigRepository.findAll(), selectableAgents(agentId),
                ChatAgent.model(effective), agentId, effective.definition().instructions()).render();

        return ResponseEntity.ok(openDialog(
                ai.mindconnect.chatui.ui.component.ChatSettingsComponent.TITLE, form));
    }

    /**
     * The agent picker changed: the form is drawn again with the model and
     * the prompt that go with the pick ({@link
     * ai.mindconnect.chatui.ui.component.ChatSettingsComponent#valuesFor}), so
     * the dialog shows what Apply will do instead of the previous agent's
     * prompt. Nothing is saved.
     */
    @PostMapping("/sessions/{sessionId}/settings/agent")
    public ResponseEntity<UiPatch> settingsAgentPicked(@PathVariable("sessionId") String sessionIdValue,
                                                       @RequestBody Map<String, Object> raw,
                                                       @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();
        var body = new FormBody(raw == null ? Map.of() : raw);
        String agentId = body.str("agentId");
        ManagedAgent picked = agentId == null || agentId.isBlank() ? null
                : agents.find(AgentId.of(agentId)).orElse(null);
        var effective = effective(session);
        AgentId bound = boundAgentId(session);
        var values = ai.mindconnect.chatui.ui.component.ChatSettingsComponent.valuesFor(picked, bound,
                new ai.mindconnect.chatui.ui.component.ChatSettingsComponent.Values(
                        ChatAgent.model(effective), effective.definition().instructions()),
                new ai.mindconnect.chatui.ui.component.ChatSettingsComponent.Values(
                        orKeep(body.str("llmConfigName"), ChatAgent.model(effective)),
                        orKeep(body.str("systemPrompt"), effective.definition().instructions())));
        var form = new ai.mindconnect.chatui.ui.component.ChatSettingsComponent(
                sessionId, llmConfigRepository.findAll(), selectableAgents(bound),
                values.llmConfigName(), ChatAgent.id(picked), values.systemPrompt());
        return ResponseEntity.ok(UiPatch.of().patch(UiPatch.Operation.replace(form.id(), form.render())));
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

        var effective = effective(session);
        var def = agentId == null || agentId.isBlank() ? null
                : agents.find(AgentId.of(agentId))
                        .orElseThrow(() -> new IllegalArgumentException("No such agent: " + agentId));
        // Another agent takes the chat over as it is, which is what the picker promises. The agent the
        // chat already refers to stays referred to while the form says what the agent says; a model or
        // a prompt that differs makes the chat's definition its own, as does a chat without an agent.
        boolean another = def != null && !ChatAgent.id(def).equals(boundAgentId(session));
        boolean untouched = def != null && differing(body.str("systemPrompt"), def.definition().instructions()) == null
                && differing(body.str("llmConfigName"), ChatAgent.model(def)) == null;
        if (another || untouched) {
            sessionManagement.useAgent(sessionId, def.id());
        } else {
            own(session, effective, ChatAgent.withPromptAndModel(effective.definition(),
                    orKeep(body.str("systemPrompt"), effective.definition().instructions()),
                    orKeep(body.str("llmConfigName"), ChatAgent.model(effective))));
        }
        var saved = sessionManagement.find(sessionId).orElseThrow();
        String userId = userId(user);
        return ResponseEntity.ok(
                shell(saved, chats(userId)));
    }

    /** The agent the chat's next turn runs on. */
    private ManagedAgent effective(ManagedSession chat) {
        return ChatAgent.effective(sessionManagement, agents, chat);
    }

    /** The chat runs on this definition from now on, and on no stored agent's. */
    private void own(ManagedSession chat, ManagedAgent effective,
                     ai.mindconnect.agent.runtime.api.domain.AgentDefinition changed) {
        // Written without the copy of what the earlier runtime wrote: it describes the definition before.
        sessionManagement.changeAgent(chat.id(), effective.id() == null ? changed
                : ChatAgent.identified(effective.withDefinition(changed)).definition());
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
                (session.directories().working() != null) ? session.directories().working() : "The server's default directory",
                pickerForm(session, session.directories().working())));
    }

    /** The chooser's form at {@code path}, or at the root when there is none or it cannot be listed. */
    private ai.mindconnect.ui.model.UiForm pickerForm(ManagedSession session, String path) {
        ai.mindconnect.agent.management.directory.WorkingDirBrowser.Listing listing;
        try {
            listing = dirBrowser.list(session.userId().value(), path);
        } catch (RuntimeException e) {
            listing = dirBrowser.list(session.userId().value(), null);
        }
        return ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.form(SessionOwnership.idOf(session), session, listing);
    }

    /** One step down or up in the chooser — the form alone is redrawn, at {@code path}. */
    @GetMapping("/sessions/{sessionId}/dirs")
    public ResponseEntity<UiPatch> browseDirs(@PathVariable SessionId sessionId,
                                              @RequestParam(required = false) String path,
                                              @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var listing = dirBrowser.list(sessionOpt.get().userId().value(), path);
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
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        sessionManagement.changeDirectories(sessionId,
                new SessionDirectories(pathOf(raw), sessionOpt.get().directories().additional()));
        var saved = sessionManagement.find(sessionId).orElseThrow();
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("chat-dialog"))
                .patch(composerRefresh(saved))
                .toast(ai.mindconnect.ui.model.UiToast.success((saved.directories().working() != null)
                        ? "Working directory: " + saved.directories().working()
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
        var created = dirBrowser.create(session.userId().value(), pathOf(raw), name);
        return ResponseEntity.ok(redrawnPicker(session, dirBrowser.list(session.userId().value(), created.toString()))
                .toast(ai.mindconnect.ui.model.UiToast.success("Created " + created).title("New folder")));
    }

    /** Adds the field's path to the additional directories; the dialog stays open and shows it. */
    @PostMapping("/sessions/{sessionId}/dirs/add")
    public ResponseEntity<UiPatch> addDir(@PathVariable SessionId sessionId,
                                          @RequestBody Map<String, Object> raw,
                                          @AuthenticationPrincipal OidcUser user) {
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        String path = pathOf(raw);
        if (path == null) throw new IllegalArgumentException("Type or pick a directory to add first");
        var session = sessionOpt.get();
        sessionManagement.changeDirectories(sessionId, new SessionDirectories(session.directories().working(),
                java.util.stream.Stream.concat(session.directories().additional().stream(), java.util.stream.Stream.of(path))
                        .toList()));
        var saved = sessionManagement.find(sessionId).orElseThrow();
        return ResponseEntity.ok(redrawnPicker(saved, dirBrowser.list(saved.userId().value(), path))
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
        sessionManagement.changeDirectories(sessionId, new SessionDirectories(session.directories().working(),
                session.directories().additional().stream().filter(d -> !d.equals(path)).toList()));
        var saved = sessionManagement.find(sessionId).orElseThrow();
        return ResponseEntity.ok(redrawnPicker(saved, dirBrowser.list(saved.userId().value(), saved.directories().working()))
                .toast(ai.mindconnect.ui.model.UiToast.success("Removed " + path).title("Additional directories")));
    }

    /** The session's id as the runtime and the management know it. */
    /**
     * Starts a turn in the chat and follows its session, with the sessions of
     * the sub-agents under it, until the turn is over.
     *
     * @return the turn's answer; failed when the turn failed or was cancelled
     */
    private java.util.concurrent.CompletableFuture<String> startTurn(
            SessionId sessionId, List<ai.mindconnect.agent.loop.domain.ContentPart> input,
            SessionEventListener live) {
        SessionId chat = sessionId;
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        // Followed before the turn exists: a session's events are not replayed.
        java.util.function.Consumer<SessionEvent> heard = event -> {
            live.onEvent(event);
            if (!event.sessionId().equals(chat) || event.ofSubAgent()) {
                return;
            }
            switch (event.known()) {
                case SessionStreamEvent.Done done -> answer.complete(done.text() == null ? "" : done.text());
                // A turn that dies does not end itself; its stream says how it broke off.
                case SessionStreamEvent.Error error -> answer.completeExceptionally(
                        error.status() == ai.mindconnect.agent.runtime.api.domain.TurnStatus.CANCELLED
                                ? new java.util.concurrent.CancellationException("The turn was cancelled")
                                : new IllegalStateException("The turn failed"));
                default -> { }
            }
        };
        var stream = runtimeSessions.stream(chat, true, heard::accept);
        Runnable unfollow = stream::close;
        answer.whenComplete((text, failure) -> unfollow.run());
        try {
            sessionTurns.submit(chat, input);
        } catch (RuntimeException e) {
            unfollow.run();
            throw e;
        }
        return answer;
    }

    /** The card helpers know a sub-agent's run by a UUID; its session's id gives one. */
    private static java.util.UUID taskOf(SessionId subSession) {
        return java.util.UUID.nameUUIDFromBytes(
                subSession.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The chooser's form redrawn in place. */
    private UiPatch redrawnPicker(ManagedSession session,
                                  ai.mindconnect.agent.management.directory.WorkingDirBrowser.Listing listing) {
        return UiPatch.of().patch(UiPatch.Operation.replace(
                ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.ID,
                ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.form(SessionOwnership.idOf(session), session, listing)));
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
    private UiPatch.Operation composerRefresh(ManagedSession session) {
        var agent = effective(session);
        boolean streaming = activeStreams.findHandle(
                ai.mindconnect.chatui.service.SessionOwnership.channelOf(SessionOwnership.idOf(session))).isPresent();
        var form = new ai.mindconnect.chatui.ui.component.ChatFormComponent(
                        SessionOwnership.idOf(session), ChatAgent.id(agent), streaming)
                .withModelLabel(ai.mindconnect.chatui.ui.component.ModelLabel.of(
                        llmConfigRepository, ChatAgent.model(agent)))
                .withAttachments(sessionFiles.attachments(SessionOwnership.idOf(session)))
                .withAgentCounts(agent, offeredToolNames())
                .withWorkingDir(session.directories().working())
                .withDirChoice(workingDirPolicy.allowsChoice());
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
            sessionManagement.rename(sessionId, title.trim());
        }
        String userId = userId(user);
        var sessions = chats(userId);
        var current = sessionManagement.find(sessionId).orElseThrow();
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
        sessionManagement.delete(sessionId);
        log.info("Chat {} deleted", sessionId);

        var sessions = chats(userId);
        var newest = sessions.isEmpty()
                ? java.util.Optional.<ManagedSession>empty()
                : java.util.Optional.of(sessions.get(0));
        if (newest.isEmpty()) {
            var fresh = openDefaultChat(userId);
            return ResponseEntity.ok(shell(fresh, chats(userId)));
        }
        return ResponseEntity.ok(shell(newest.get(), sessions));
    }

    /**
     * The session, but only for the user it belongs to. Every endpoint that
     * addresses a session by id goes through here: the id is the only thing
     * standing between one user's chat and another's, and an id is not a
     * secret — it travels in URLs, links and logs.
     */
    private java.util.Optional<ManagedSession> ownedSession(
            SessionId sessionId, OidcUser user) {
        return sessionManagement.find(sessionId)
                .filter(session -> SessionOwnership.owns(session, user));
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

    /**
     * The user's chats for the history drawer, newest first — from the
     * management API, which lists what the runtime and the chat both keep.
     */
    private List<ManagedSession> chats(String userId) {
        return sessionManagement.list(UserId.of(userId)).stream()
                .filter(session -> ManagedSession.CHAT.equals(session.type()))
                .toList();
    }

    private UiPage shell(ManagedSession session,
                         List<ManagedSession> sessions) {
        return shell(session, sessions, ai.mindconnect.chatui.ui.component.ChatShellComponent.History.CHAT);
    }

    private UiPage shell(ManagedSession session,
                         List<ManagedSession> sessions,
                         ai.mindconnect.chatui.ui.component.ChatShellComponent.History history) {
        var seen = rememberShown(SessionOwnership.idOf(session));
        var agent = effective(session);
        var chat = buildChatPage(session, agent);
        var appShell = new ai.mindconnect.chatui.ui.component.ChatShellComponent(
                sessions, session, agent.definition().name(), chat.renderContent(), agentIcons())
                .withActivity(runningSessions(sessions), waitingSessions(sessions))
                .withUnseen(seen.unseen(sessions))
                .withHistory(history)
                .render();
        var page = UiPage.of("/chat/sessions/" + SessionOwnership.idOf(session).value(), appShell);
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

    private ManagedSession openDefaultChat(String userId) {
        return agents.findByName(DEFAULT_CHAT_AGENT)
                .map(agent -> chatOpener.open(ChatAgent.id(agent), UserId.of(userId), null, null, List.of()))
                .orElseGet(() -> sessionManagement.open(UserId.of(userId),
                        null, new SessionAgent.Own(fallbackChatAgent(), null), null, SessionDirectories.NONE));
    }

    /**
     * What a chat can do before anyone switches anything on. It used to live
     * on the settings component, next to the tool multiselect that read it;
     * the multiselect is gone (tools are switched in the "+" menu now) and
     * the default belongs to whoever opens a chat.
     */
    private static final List<String> DEFAULT_TOOLS = List.of("todo_read", "todo_write");

    /** How many rounds a turn of the fallback chat agent may take. */
    private static final int FALLBACK_MAX_ROUNDS = 10;

    /** The fallback chat agent: the standard model, the standard tools, nobody to delegate to. */
    private ai.mindconnect.agent.runtime.api.domain.AgentDefinition fallbackChatAgent() {
        var known = allToolNames();
        String model = defaultLlmConfigName();
        return new ai.mindconnect.agent.runtime.api.domain.AgentDefinition("Chat", "", CHAT_SYSTEM_PROMPT,
                model == null ? ai.mindconnect.agent.runtime.api.domain.ModelSettings.NONE
                        : ai.mindconnect.agent.runtime.api.domain.ModelSettings.ofModel(model),
                DEFAULT_TOOLS.stream().filter(known::contains)
                        .map(name -> AgentToolBinding.of(ai.mindconnect.agent.tool.AgentTool.of(name)))
                        .toList(),
                FALLBACK_MAX_ROUNDS,
                // Its own id, as every agent has one: the tools read whose agent calls them.
                Map.of(ManagedAgent.AGENT_ID, java.util.UUID.randomUUID().toString(),
                        SubAgentPlugin.SUB_AGENTS, List.of()));
    }

    /** The default prompt of a chat that has no agent behind it. */
    private static final String CHAT_SYSTEM_PROMPT = """
            You are a helpful assistant. Be concise and practical.

            Today's date: {{ current_date }}

            Use `todo_write` to publish a plan before starting anything with
            several steps.
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
     * The chat's tool selection as bindings, reusing the agent's own binding
     * for every name it already has.
     *
     * <p>Rebuilding them from bare names would quietly drop what the binding
     * carries beyond the name — {@code needsApproval} on bash and
     * {@code code_execute}, the {@code mountDir} that gives the sandbox its
     * host directory, a deferred flag. Turning the model dropdown would have
     * been enough to lose all of it.
     */
    private List<ToolBinding> pickTools(ManagedAgent def, java.util.Map<String, Boolean> chosen) {
        var byName = def.definition().tools().stream().collect(java.util.stream.Collectors.toMap(
                ToolBinding::name, t -> t, (a, b) -> a));
        var known = allToolNames();
        var picked = new java.util.LinkedHashMap<String, ToolBinding>();
        // Whatever the dialog could not show stays: the chat did not drop it,
        // it was never asked about. Without this, opening the settings and
        // pressing Apply would silently strip an agent's Gmail tools on a
        // machine where Gmail is not configured.
        for (var t : def.definition().tools()) {
            if (!known.contains(t.name())) {
                picked.put(t.name(), t);
            }
        }
        chosen.forEach((n, deferred) -> {
            if (!known.contains(n)) {
                return;
            }
            var bound = byName.get(n);
            if (bound == null) {
                bound = AgentToolBinding.of(ai.mindconnect.agent.tool.AgentTool.of(n));
            }
            // The same binding, offered up front or left to search.
            picked.put(n, bound.deferred() == deferred ? bound : new ToolBinding(bound.name(), deferred, bound.metadata()));
        });
        return List.copyOf(picked.values());
    }

    /** A definition's tools as the pickers edit them: name &rarr; deferred, in order. */
    private static java.util.LinkedHashMap<String, Boolean> selection(ManagedAgent def) {
        var out = new java.util.LinkedHashMap<String, Boolean>();
        def.definition().tools().forEach(t -> out.putIfAbsent(t.name(), t.deferred()));
        return out;
    }
    /**
     * Does the chat run on a definition of its own that was taken from a
     * stored agent? Whatever it changed — the prompt, the model, one tool —
     * it no longer follows that agent, and the header must not claim it does.
     */
    private static boolean ownSettings(ManagedSession session) {
        return session.agent() instanceof SessionAgent.Own own && own.origin() != null;
    }

    /**
     * The stored agent this chat refers to, or {@code null} for a chat that
     * runs on a definition of its own — also one that was taken from a
     * stored agent and changed since.
     */
    private AgentId boundAgentId(ManagedSession session) {
        return session.agent() instanceof SessionAgent.Ref ref ? ref.id() : null;
    }

    /**
     * Icon name per agent-definition id, for the history drawer. Read from the
     * registry in one go: a row only needs the icon, and resolving every
     * session's agent separately would be one lookup per conversation.
     */
    private java.util.Map<AgentId, String> agentIcons() {
        var icons = new java.util.HashMap<AgentId, String>();
        for (ManagedAgent a : agents.list()) {
            icons.put(ChatAgent.id(a), ChatAgent.icon(a));
        }
        return icons;
    }

    private List<ManagedAgent> selectableAgents(AgentId currentAgentId) {
        return agents.list().stream()
                .filter(a -> a.status() != ManagedAgent.Status.DEPRECATED)
                .filter(a -> CHAT_GROUP.equals(ChatAgent.group(a)) || ChatAgent.id(a).equals(currentAgentId))
                .toList();
    }

    /** The one rubric whose agents a person opens a chat with. */
    private static final String CHAT_GROUP = "assistants";

    /** Every tool a chat can be given: the registry's. */
    private List<String> allToolNames() {
        var names = new java.util.TreeSet<String>();
        toolRegistry.toolNamesByGroup().values().forEach(names::addAll);
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
        return startSession(agentIdValue, user, ManagedSession.CHAT);
    }

    /**
     * Starts a session of {@code type} with the agent and shows it in the chat —
     * for a feature that hosts its own conversations in the chat's page, such
     * as a builder. A type other than {@link ManagedSession#CHAT} keeps the
     * session out of the chat's history and out of what {@code /chat} opens.
     */
    public ResponseEntity<UiPage> startSession(String agentIdValue, OidcUser user, String type) {
        return startSession(agentIdValue, user, type,
                ai.mindconnect.chatui.ui.component.ChatShellComponent.History.CHAT);
    }

    /**
     * As {@link #startSession(String, OidcUser, String)}, with the history
     * drawer leading where the feature says — its own URLs for a new
     * conversation and for each row, so the drawer never leaves the feature.
     */
    public ResponseEntity<UiPage> startSession(String agentIdValue, OidcUser user, String type,
                                               ai.mindconnect.chatui.ui.component.ChatShellComponent.History history) {
        AgentId agentId = AgentId.of(agentIdValue);
        String userId = user.getPreferredUsername();
        return agents.find(agentId)
                .map(agent -> {
                    var session = chatOpener.open(agentId, UserId.of(userId), type, null, List.of());
                    return ResponseEntity.ok(shell(session, historyOf(UserId.of(userId), session), history));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The conversations the drawer lists beside this one: the chat's own for a
     * chat; for a session of another type, the user's conversations of that
     * type with the same agent — a builder's drawer shows that builder's
     * conversations, not the chats and not the other builder's.
     */
    private List<ManagedSession> historyOf(UserId userId, ManagedSession session) {
        if (ManagedSession.CHAT.equals(session.type())) {
            return chats(userId.value());
        }
        String agentId = session.agentId().map(id -> id.value()).orElse(null);
        return sessionManagement.list(userId).stream()
                .filter(listed -> session.type().equals(listed.type())
                        && java.util.Objects.equals(listed.agentId().map(id -> id.value()).orElse(null), agentId))
                .toList();
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
        // Pictures and documents are kept apart, as in the "+" menu: each
        // dialog lists only its own kind. The endpoint is the same, and so
        // is the record behind both lists — two panels over one set of
        // files, each under its own id, which the upload and remove patches
        // both refresh.
        var listed = imagesOnly
                ? ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.Kind.IMAGES
                : ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.Kind.DOCUMENTS;
        var body = ai.mindconnect.ui.model.UiStack.of("chat-attach-body");
        body.gap(12);
        body.child(ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent
                .node(sessionId, listed, sessionFiles.attachments(sessionId), sessionFiles.listAttachments(sessionId)));
        body.child(ai.mindconnect.chatui.ui.page.ChatPage.attachZone(sessionId, imagesOnly));
        return ResponseEntity.ok(openDialog(imagesOnly ? "Add images" : "Attached files", body));
    }

    // ── The "+" menu's pickers ─────────────────────────────────────────
    //
    // The chat's agent is a template. Both pickers offer everything there is,
    // start from what the agent brought, and write the chat's own choice once
    // something is switched — which may well include tools and agents the
    // agent never had.

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
    private ai.mindconnect.ui.model.UiNode toolsPicker(ManagedSession session) {
        var effective = effective(session);
        var byGroup = offeredToolsByGroup();
        var subgroups = new java.util.HashMap<String, String>();
        byGroup.values().forEach(names -> names.forEach(name -> {
            String subgroup = toolRegistry.subgroupOf(name);
            if (subgroup != null && !subgroup.isBlank()) subgroups.put(name, subgroup);
        }));
        return ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.node(SessionOwnership.idOf(session), byGroup,
                ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.states(effective), subgroups);
    }

    /** Every tool the picker has a row for — what the composer's tool badge counts over. */
    private java.util.Set<String> offeredToolNames() {
        return ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.offered(offeredToolsByGroup());
    }

    /**
     * The registry's tools by group, without the ones the runtime derives:
     * the delegation tools follow the sub-agent picker, {@code tool_search}
     * follows whatever is set to Search. A row for either would be a switch
     * that argues with another one.
     */
    private java.util.TreeMap<String, java.util.Set<String>> offeredToolsByGroup() {
        var byGroup = new java.util.TreeMap<String, java.util.Set<String>>();
        toolRegistry.toolNamesByGroup().forEach((group, names) -> {
            var visible = new java.util.TreeSet<>(names);
            visible.removeAll(ChatAgent.DERIVED_TOOLS);
            if (!visible.isEmpty()) byGroup.put(group, visible);
        });
        return byGroup;
    }

    /** One tool off, on, or left for tool search to find. The picker stays open. */
    @PostMapping("/sessions/{sessionId}/tools")
    public ResponseEntity<UiPatch> setTool(@PathVariable("sessionId") String sessionIdValue,
                                           @RequestParam String tool,
                                           @RequestParam ToolState state,
                                           @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = effective(sessionOpt.get());
        var chosen = selection(effective);
        apply(chosen, tool, state);
        var saved = replaceTools(sessionOpt.get(), chosen, null);
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.BODY_ID,
                toolsPicker(saved)));
    }

    /**
     * Every tool of a group set at once. It sets the tools the group holds
     * now; one that joins the group later is not switched on by this.
     */
    @PostMapping("/sessions/{sessionId}/tools/group")
    public ResponseEntity<UiPatch> setToolGroup(@PathVariable("sessionId") String sessionIdValue,
                                                @RequestParam String group,
                                                @RequestParam ToolState state,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = effective(sessionOpt.get());
        var chosen = selection(effective);
        offeredToolsByGroup().getOrDefault(group, java.util.Set.of())
                .forEach(tool -> apply(chosen, tool, state));
        var saved = replaceTools(sessionOpt.get(), chosen, null);
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.BODY_ID,
                toolsPicker(saved)));
    }

    private static void apply(java.util.Map<String, Boolean> chosen, String tool, ToolState state) {
        switch (state) {
            case OFF -> chosen.remove(tool);
            case ON -> chosen.put(tool, false);
            case SEARCH -> chosen.put(tool, true);
        }
    }

    /** Every agent this chat could hand work to, with the ones it may switched on. */
    @GetMapping("/sessions/{sessionId}/subagents-dialog")
    public ResponseEntity<UiPatch> subAgentsDialog(@PathVariable("sessionId") String sessionIdValue,
                                                   @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(openDialog("Sub-agents", subAgentsPicker(sessionOpt.get())));
    }

    /** The sub-agent picker's body for the session as it stands right now. */
    private ai.mindconnect.ui.model.UiNode subAgentsPicker(ManagedSession session) {
        var effective = effective(session);
        return ai.mindconnect.chatui.ui.component.ChatSubAgentsComponent.node(
                SessionOwnership.idOf(session), delegatableAgents(effective),
                java.util.Set.copyOf(ChatAgent.roster(effective)));
    }

    /**
     * One agent on or off. The roster is the chat's from here on; the
     * delegation tools come and go with it in the runtime, so there is
     * nothing else to write.
     */
    @PostMapping("/sessions/{sessionId}/subagents")
    public ResponseEntity<UiPatch> setSubAgent(@PathVariable("sessionId") String sessionIdValue,
                                               @RequestParam String agent,
                                               @RequestParam boolean on,
                                               @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var effective = effective(sessionOpt.get());
        var roster = new java.util.LinkedHashSet<>(ChatAgent.roster(effective));
        if (on) {
            roster.add(agent);
        } else {
            roster.removeIf(agent::equalsIgnoreCase);
        }
        var saved = replaceTools(sessionOpt.get(), selection(effective), new Roster(List.copyOf(roster)));
        return ResponseEntity.ok(afterToolChange(saved,
                ai.mindconnect.chatui.ui.component.ChatSubAgentsComponent.BODY_ID,
                subAgentsPicker(saved)));
    }

    /**
     * The agents a chat may be given: every one that lets itself be called
     * by other agents, except the chat's own and the deprecated ones — a
     * picker is a list of things you can pick.
     */
    private List<ManagedAgent> delegatableAgents(ManagedAgent effective) {
        return agents.list().stream()
                .filter(a -> a.status() != ManagedAgent.Status.DEPRECATED)
                .filter(a -> SubAgentPlugin.callable(a.definition()))
                .filter(a -> !a.id().equals(effective.id()))
                .toList();
    }

    /** How long the Test dialog waits for an agent's answer before giving up. */
    private static final java.time.Duration SUBAGENT_TEST_TIMEOUT = java.time.Duration.ofMinutes(3);

    /** Test on a sub-agent: a dialog that sends it one message. */
    @GetMapping("/sessions/{sessionId}/subagents/test-dialog")
    public ResponseEntity<UiPatch> subAgentTestDialog(@PathVariable("sessionId") String sessionIdValue,
                                                      @RequestParam String agent,
                                                      @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var def = agents.findByName(agent);
        if (def.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(openDialog("Test " + def.get().definition().name(),
                ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.node(
                        sessionId, def.get(), null, null)));
    }

    /**
     * Sends the message to the agent and waits for its answer, which the
     * dialog then shows under the form.
     *
     * <p>The agent runs in a session of its own under this chat, the way a
     * sub-agent does — so it works in the chat's directories and stays out
     * of the chat history — and that session is deleted once the answer is
     * back, together with any sub-agents it started. A tool that needs
     * approval asks in the chat behind the dialog; an answer that has not
     * come within {@link #SUBAGENT_TEST_TIMEOUT} is given up on.
     */
    @PostMapping("/sessions/{sessionId}/subagents/test")
    public ResponseEntity<UiPatch> testSubAgent(@PathVariable("sessionId") String sessionIdValue,
                                                @RequestParam String agent,
                                                @RequestBody(required = false) Map<String, Object> raw,
                                                @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        var sessionOpt = ownedSession(sessionId, user);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var defOpt = agents.findByName(agent);
        if (defOpt.isEmpty()) return ResponseEntity.notFound().build();
        var def = defOpt.get();
        String message = raw == null ? null : new FormBody(raw).str("message");
        ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result result;
        if (message == null || message.isBlank()) {
            result = ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result.failed(
                    "Type a message first.", 0);
        } else {
            result = runSubAgentTest(sessionOpt.get(), def, message.strip());
        }
        return ResponseEntity.ok(UiPatch.of().patch(UiPatch.Operation.replace(
                ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.BODY_ID,
                ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.node(
                        sessionId, def, message, result))));
    }

    private ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result runSubAgentTest(
            ManagedSession chat, ManagedAgent def, String message) {
        long start = System.currentTimeMillis();
        ManagedSession test = null;
        try {
            test = sessionManagement.open(chat.userId(), chat.id(), new SessionAgent.Ref(def.id()), null,
                    chat.directories());
            // A title of its own spares the title generator a call for a
            // session that is gone again in a minute.
            sessionManagement.rename(test.id(), "Test: " + def.definition().name());
            String answer = startTurn(SessionOwnership.idOf(test), List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text(message)),
                    event -> { })
                    .get(SUBAGENT_TEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result.answered(
                    answer, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException e) {
            sessionTurns.cancel(test.id());
            return ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result.failed(
                    "No answer within " + SUBAGENT_TEST_TIMEOUT.toMinutes() + " minutes — if a tool is "
                            + "waiting for approval, it asks in the chat behind this dialog.",
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (test != null) sessionTurns.cancel(test.id());
            return ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result.failed(
                    "Interrupted", System.currentTimeMillis() - start);
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause() : e;
            log.warn("Sub-agent test of '{}' failed: {}", def.definition().name(), cause.getMessage());
            return ai.mindconnect.chatui.ui.component.ChatSubAgentTestComponent.Result.failed(
                    cause.getMessage(), System.currentTimeMillis() - start);
        } finally {
            if (test != null) {
                deleteWithSubSessions(SessionOwnership.idOf(test));
            }
        }
    }

    /** A session and every session it started, deepest first; best effort. */
    private void deleteWithSubSessions(SessionId sessionId) {
        for (ManagedSession child : sessionManagement.children(sessionId)) {
            deleteWithSubSessions(SessionOwnership.idOf(child));
        }
        try {
            sessionManagement.delete(sessionId);
        } catch (RuntimeException e) {
            log.warn("Could not delete test session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * What every tool change answers with: the picker redrawn from the saved
     * session, and the composer too — its "+" carries the tool count, which
     * would otherwise keep stating what was true before the click.
     */
    private UiPatch afterToolChange(ManagedSession saved, String bodyId,
                                    ai.mindconnect.ui.model.UiNode body) {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(bodyId, body))
                .patch(composerRefresh(saved));
    }

    /** A roster to write: the agents this chat may call; an empty list is nobody. */
    private record Roster(List<String> names) {}

    /**
     * This chat's tools replaced — and its roster, when one is passed.
     * Everything else the chat chose (its model, its prompt, the agent behind
     * it) is carried over untouched, which is why this cannot just call the
     * settings dialog's handler: that one also decides who the chat's agent is.
     *
     * @param chosen tool name &rarr; deferred, the tools the chat carries
     * @param roster {@code null} keeps the chat's roster as it is
     */
    private ManagedSession replaceTools(ManagedSession session, java.util.Map<String, Boolean> chosen,
                                      Roster roster) {
        var effective = effective(session);
        // pickTools against the effective definition: it carries what this chat chose before, so a
        // binding's approval flag or mount directory survives the next click.
        var changed = ChatAgent.withTools(effective.definition(), pickTools(effective, chosen));
        if (roster != null) {
            changed = SubAgentPlugin.withSubAgents(changed, roster.names());
        }
        // Switching a tool on and straight back off writes nothing: a chat that refers to its agent
        // keeps referring to it until something really differs.
        if (!changed.equals(effective.definition())) {
            own(session, effective, changed);
        }
        return sessionManagement.find(session.id()).orElseThrow();
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
        return getSession(sessionIdValue, user, ai.mindconnect.chatui.ui.component.ChatShellComponent.History.CHAT);
    }

    /**
     * Shows a session with the history drawer leading where the feature says
     * — for a feature that hosts conversations of its own type in this page,
     * such as a builder. The drawer lists the user's conversations of the
     * session's type with the same agent.
     */
    public ResponseEntity<UiPage> getSession(String sessionIdValue, OidcUser user,
                                             ai.mindconnect.chatui.ui.component.ChatShellComponent.History history) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        return ownedSession(sessionId, user)
                .map(session -> ResponseEntity.ok(shell(session,
                        historyOf(UserId.of(userId(user)), session), history)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Loads the current history + memory snapshot for the session and
     * wraps them in a {@link ChatPage} bound to the given agent. All
     * chat-page rendering and patch generation goes through this single
     * factory so the controller never reaches for the model directly.
     */
    private ChatPage buildChatPage(ManagedSession session,
                                   ManagedAgent agent) {
        var history = sessionManagement.messages(session.id());
        var memory  = safeMemorySnapshot(SessionOwnership.idOf(session));
        // Source-of-truth for "is this turn currently streaming?" is the
        // registry — not page-local state. Lets a navigate-back during a
        // live turn render the form in Stop-mode without any client-side
        // reconciliation.
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(SessionOwnership.idOf(session));
        var handleOpt = activeStreams.findHandle(channelId);
        var page = new ChatPage(session, agent, history, memory, handleOpt.isPresent(),
                (toolCallId, running, in, out) ->
                        buildSubAgentCards(SessionOwnership.idOf(session), toolCallId, running, in, out),
                objectMapper)
                .withModelLabel(ai.mindconnect.chatui.ui.component.ModelLabel.of(
                        llmConfigRepository, ChatAgent.model(agent)))
                .withOwnSettings(ownSettings(session))
                .withBubbledApprovals(bubbledApprovalCards(SessionOwnership.idOf(session)))
                .withHostLinks(hostLinks)
                .withOfferedTools(offeredToolNames());
        page.withAttachments(sessionFiles.attachments(SessionOwnership.idOf(session)));
        page.withDirChoice(workingDirPolicy.allowsChoice());
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
        String agentLabel = agent.definition().name() != null ? agent.definition().name() : "Agent";
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
                        "/chat/sessions/" + SessionOwnership.idOf(session).value(),
                        returnLabel)));
        return page;
    }

    /** The sessions with a turn in flight — the stream registry is the truth, as for the Stop button. */
    private java.util.Set<SessionId> runningSessions(List<ManagedSession> sessions) {
        java.util.Set<SessionId> running = new java.util.HashSet<>();
        for (var s : sessions) {
            SessionId id = s.id();
            if (activeStreams.findHandle(ai.mindconnect.chatui.service.SessionOwnership.channelOf(id)).isPresent()) running.add(id);
        }
        return running;
    }

    /** The sessions with a tool stopped at the approval gate — the waiting turn is the truth, as for the cards. */
    private java.util.Set<SessionId> waitingSessions(List<ManagedSession> sessions) {
        java.util.Set<SessionId> waiting = new java.util.HashSet<>();
        for (var s : sessions) {
            SessionId id = s.id();
            if (!openApprovals(id).isEmpty()) waiting.add(id);
        }
        return waiting;
    }

    /**
     * The cards for this session's open approval questions, its own and
     * those of the sub-agents under it — read from the turn that waits for
     * them (a question exists = card shows; answered or cancelled = gone).
     */
    private List<ai.mindconnect.ui.model.UiList.Item> bubbledApprovalCards(SessionId sessionId) {
        return openApprovals(sessionId).stream().map(open -> approvalCard(sessionId, open)).toList();
    }

    /** The tool calls the session's turn waits for an approval of. */
    private List<Interruption> openApprovals(SessionId sessionId) {
        return sessionTurns.open(sessionId).stream()
                .filter(open -> Interruption.TOOL_APPROVAL.equals(open.kind()) && open.call() != null)
                .toList();
    }

    /** The card that asks for the approval of one tool call. */
    private ai.mindconnect.ui.model.UiList.Item approvalCard(SessionId sessionId, Interruption interruption) {
        return approvalCard(sessionId, interruption.call().callId(), interruption.call().toolName(),
                interruption.call().arguments());
    }

    private ai.mindconnect.ui.model.UiList.Item approvalCard(SessionId sessionId, String callId,
                                                                    String toolName, Map<String, Object> arguments) {
        String argsJson;
        try {
            argsJson = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(arguments);
        } catch (Exception e) {
            argsJson = String.valueOf(arguments);
        }
        return ai.mindconnect.chatui.ui.component.ApprovalCardComponent.approvalCard(
                sessionId, callId, toolName, argsJson,
                ai.mindconnect.chatui.ui.SessionUiCommons.DT_FMT.format(java.time.Instant.now()));
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
        List<ManagedSession> children;
        try {
            children = sessionManagement.children(parentSessionId).stream()
                    .filter(s -> toolCallId.equals(s.metadata().get(SubAgentPlugin.OPENED_BY_CALL)))
                    .sorted(java.util.Comparator.comparing(
                            ManagedSession::createdAt))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list sub-sessions of {} for toolCall {}: {}",
                    parentSessionId, toolCallId, e.getMessage());
            return List.of();
        }

        List<TaskCardComponent> cards = new java.util.ArrayList<>();
        for (var child : children) {
            try {
                var childAgent = sessionManagement.agentOf(child.id()).map(ManagedAgent::of).orElse(null);
                String agentName = childAgent != null ? childAgent.definition().name() : "sub-agent";
                var childHistory = sessionManagement.messages(child.id());

                // A throwaway component reuses all the historic-card grouping
                // logic for the sub-session, recursing through the same
                // provider scoped to THIS child's id. A nested sub-agent's
                // running state likewise follows its own parent-result
                // presence, computed inside that recursive call.
                var childComp = new ai.mindconnect.chatui.ui.component.MessageListComponent(
                        SessionOwnership.idOf(child), childAgent, childHistory, null,
                        (tcId, r, in, out) -> buildSubAgentCards(SessionOwnership.idOf(child), tcId, r, in, out),
                        objectMapper);

                var childList = TaskCardComponent.subAgentChildList(SessionOwnership.idOf(child).value());
                for (TaskCardComponent t : childComp.allHistoricTaskCards()) {
                    childList.item(((UiList) t.render()).getItems().get(0));
                }
                // The thought behind the sub-agent's answer, where the live
                // stream put it: after its tools, ahead of the answer.
                if (!running) {
                    childComp.lastAssistantThinkingCard().ifPresent(card ->
                            childList.item(((UiList) card.render()).getItems().get(0)));
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
                String nodeId = "task-sub-" + SessionOwnership.idOf(child).value();
                cards.add(TaskCardComponent.historicSubAgent(
                        nodeId, agentName, SessionOwnership.idOf(child).value(),
                        running, failed, 0L, childInput, resultText,
                        childList, finalText));
            } catch (Exception e) {
                log.warn("Failed to build sub-agent card for session {}: {}",
                        SessionOwnership.idOf(child), e.getMessage());
            }
        }
        return cards;
    }

    /** The first USER CHAT message in a (sub-)session — the task it was given. */
    private static String firstUserText(List<ai.mindconnect.agent.management.api.ManagedMessage> history) {
        return history.stream()
                .filter(m -> m.message().type() == ai.mindconnect.agent.loop.domain.TurnMessage.Type.USER)
                .min(java.util.Comparator.comparingInt(ai.mindconnect.agent.management.api.ManagedMessage::sequence))
                .map(m -> m.message().text())
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
        var agentOpt = java.util.Optional.of(effective(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agent = agentOpt.get();

        try (var ctx = LoggingContext.session(sessionId, null, agent.definition().name())) {
            startTurn(sessionId, List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text(text)), event -> { })
                    .join();
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
        boolean cancelled = sessionTurns.cancel(sessionId);
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
        var agentOpt = java.util.Optional.of(effective(sessionOpt.get()));
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();

        if (toSeq != Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Messages are deleted from one on to the end of the conversation");
        }
        sessionManagement.deleteMessagesFrom(sessionId, fromSeq);

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
        var agentOpt = java.util.Optional.of(effective(sessionOpt.get()));
        if (agentOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return runChatStream(sessionOpt.get(), agentOpt.get(), text, false);
    }

    /**
     * The answer to ANY approval card. The callId is the whole identity —
     * tool task, tool name and origin live in the ToolApprovalRepository. No new
     * stream: the turn never ended (it is suspended on the parked tool task)
     * and its original stream carries the continuation; this delivers the
     * decision and removes the card. A STALE card (no store entry any more)
     * delivers nothing — the removal alone drops it.
     *
     * <p>Only the card goes, never the whole list: the woken tool task streams
     * its card into the page at once, and a list rebuilt from history — slower
     * than that, and blind to a tool whose result is not saved yet — would
     * land on top of it and wipe the running tool from the chat.
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
        boolean delivered = sessionTurns.answerApproval(sessionId, callId, approved,
                "session".equalsIgnoreCase(scope)).isPresent();
        log.info("POST /chat/api/sessions/{}/approval call={} approved={} scope={} delivered={}",
                sessionId.value(), callId, approved, scope, delivered);
        return ResponseEntity.ok(ai.mindconnect.chatui.ui.component.MessageListComponent.removeApprovalCard(callId));
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
        var agentOpt = java.util.Optional.of(effective(sessionOpt.get()));
        if (agentOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Capture the user message at `seq` before we delete it — its parts,
        // so an image sent with it is sent again.
        java.util.List<ai.mindconnect.agent.loop.domain.ContentPart> parts = sessionManagement.messages(sessionId).stream()
                .filter(m -> m.sequence() == seq)
                .filter(m -> m.message().type() == ai.mindconnect.agent.loop.domain.TurnMessage.Type.USER)
                .map(m -> m.message().content())
                .findFirst()
                .orElse(null);
        String text = parts == null ? null : parts.stream()
                .filter(ai.mindconnect.agent.loop.domain.ContentPart.Text.class::isInstance).map(part -> ((ai.mindconnect.agent.loop.domain.ContentPart.Text) part).text())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (parts == null || (text.isBlank() && parts.size() == 1)) {
            return ResponseEntity.badRequest().build();
        }

        // Drop this message and everything after it; the turn below re-adds
        // the user message fresh, so deleting from `seq` inclusive avoids a
        // duplicate. toSeq = MAX_VALUE → delete to the end.
        sessionManagement.deleteMessagesFrom(sessionId, seq);
        log.info("Regenerate session={} from seq={} (deleted to end, re-running turn)", sessionId, seq);

        // initialRefresh=true: push the trimmed conversation to the client
        // before streaming, so the now-deleted messages disappear from the
        // DOM instead of lingering until the end-of-turn refresh.
        var session = sessionOpt.get();
        return runTurnStream(session, agentOpt.get(), parts, true,
                handler -> startTurn(SessionOwnership.idOf(session), parts, handler));
    }

    /**
     * The user bubble a turn shows the moment it is sent, as the message will
     * be stored: the attachments added or removed since the last turn are
     * announced with it, and a fresh image or PDF rides along as a part — the
     * same rules the runtime applies when it writes the message, read off the
     * same session and history, so the picture is there before the answer
     * rather than after the turn.
     */
    private String userBubble(ManagedSession session, java.util.List<ai.mindconnect.agent.loop.domain.ContentPart> parts) {
        var id = session.id();
        var history = sessionManagement.messages(id).stream()
                .map(ai.mindconnect.agent.management.api.ManagedMessage::message).toList();
        var attached = ai.mindconnect.agent.runtime.capabilities.attachment.Attachment.of(
                sessionManagement.find(id).orElseThrow().metadata());
        return ai.mindconnect.chatui.ui.component.MessageComponent.userBubble(SessionOwnership.idOf(session),
                ai.mindconnect.agent.runtime.capabilities.attachment.AttachmentPlugin.userMessage(
                        attached, history, parts, Map.of()));
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
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runChatStream(ManagedSession session,
                                                                          ManagedAgent agent,
                                                                          String text, boolean initialRefresh) {
        return runTurnStream(session, agent, List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text(text)), initialRefresh,
                handler -> startTurn(SessionOwnership.idOf(session), List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text(text)), handler));
    }

    /**
     * The streaming core, parameterised over WHAT starts the turn: a typed
     * message ({@code userParts} echoed as a user bubble) or an approval answer
     * ({@code userParts == null} — the card click is the input, nothing to echo).
     */
    private ResponseEntity<ai.mindconnect.ui.model.UiPatch> runTurnStream(ManagedSession session,
                                                                          ManagedAgent agent,
                                                                          java.util.List<ai.mindconnect.agent.loop.domain.ContentPart> userParts,
                                                                          boolean initialRefresh,
                                                                          java.util.function.Function<SessionEventListener, java.util.concurrent.CompletableFuture<String>> turnStarter) {
        SessionId sessionId = SessionOwnership.idOf(session);
        // Channel id == the id of the message-list container the patches
        // target. This way the client's {@code findStreamTarget} lookup
        // naturally detects "chat page mounted", and both the submitter and
        // any observer resolve the same stream.
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId);
        String returnHref = "/chat/sessions/" + sessionId.value();
        String streamLabel = agent.definition().name() != null ? agent.definition().name() : "Agent";
        // Every node this turn puts on the page carries an id no earlier turn
        // used. A turn that fails or is cancelled leaves its live nodes where
        // they are — there is no rebuild to swap them for history — and the
        // next turn's patches, aimed at a reused id, landed in the first
        // element with it: the old turn's card or reply bubble, while the new
        // one sat empty below.
        String turnKey = liveNodeKey();
        String pendingId  = "bot-pending-"  + sessionId.value() + "-" + turnKey;
        String typingId   = "bot-typing-"   + sessionId.value() + "-" + turnKey;

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
        // Cancellation goes through the same path
        // the legacy DELETE /sessions/{id}/chat endpoint uses — no separate
        // mechanism to keep in sync.
        activeStreams.register(new ai.mindconnect.chatui.service.ActiveStreams.Handle(
                channelId,
                streamLabel,
                returnHref,
                java.time.Instant.now(),
                () -> sessionTurns.cancel(sessionId),
                bus));

        // 0. Regenerate only: replace the message list with the trimmed
        //    (post-delete) history so the discarded messages vanish before the
        //    new user message + reply stream in.
        if (initialRefresh) {
            publishPatch(bus, liveView.headerOnly());
        }

        // 1. Append user message (a typed turn) and the typing bubble, or
        //    just swap the form to streaming (an approval resume).
        if (userParts != null) {
            publishPatch(bus, liveView.streamStart(userBubble(session, userParts), typingId));
            // The catch-up frame until the first token replaces it: a client
            // that opens the page now gets the bubble too — nothing it renders
            // from history has one.
            sessionStreams.rememberBubble(channelId, json(liveView.streamTyping(typingId)));
        } else {
            publishPatch(bus, liveView.streamResume());
        }

        // 2. Stream tokens + per-task cards.
        StringBuilder cumulativeText = new StringBuilder();
        /** Tracks whether the bot-pending item has been appended yet. */
        final boolean[] pendingAppended = new boolean[]{false};
        // The model's reasoning, one card per stretch of thinking and per
        // scope (the top-level agent, each running sub-agent): opened by the
        // first Thinking delta, closed by whatever the thought led to — the
        // first token, a tool call, an approval question, the end of the
        // turn. A later stretch — thinking again after a tool result — gets a
        // card of its own.
        java.util.Map<String, LiveThinking> thinkings = new java.util.HashMap<>();

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
        // One event at a time. Parallel sub-agents (run_agents) deliver their events from
        // several task-queue workers at once, and the live state above — the thinking
        // cards, the open tasks, the reply so far — lives in plain maps and fields: a
        // lost entry left a card running for good, and iterating while another thread
        // wrote threw inside the handler.
        final Object liveLock = new Object();
        Consumer<StreamEvent> onEvent = event -> {
            synchronized (liveLock) {
                // A thought ends with whatever it led to: anything that is not
                // more thinking closes the open card of this scope.
                LiveThinking thinking = thinkingFor(thinkings, sessionId, null);
                if (!(event instanceof TurnStreamEvent.Thinking)) {
                    closeThinking(thinking, liveView, bus, event instanceof TurnStreamEvent.TextDelta);
                }
                switch (event) {
                    case TurnStreamEvent.Thinking reasoning ->
                        onThinking(thinking, null, reasoning.text(), liveView, bus);
                    case TurnStreamEvent.TextDelta text -> {
                        cumulativeText.append(text.text());
                        if (!pendingAppended[0]) {
                            // First token: drop the typing bubble and append
                            // the streaming bot-reply placeholder BELOW any task
                            // cards that arrived during the thinking phase.
                            // Kept as the catch-up frame: a client that opens the
                            // page mid-turn has no bubble, and every token after
                            // it is a REPLACE that would land nowhere.
                            sessionStreams.rememberBubble(channelId,
                                    publishPatch(bus, liveView.streamFirstToken(pendingId, typingId)));
                            pendingAppended[0] = true;
                        }
                        // Token patches carry the CUMULATIVE text, so the newest
                        // one alone restores the full reply so far.
                        sessionStreams.rememberText(channelId,
                                publishPatch(bus, liveView.streamToken(pendingId, cumulativeText.toString())));
                    }
                    case TurnStreamEvent.MessageWritten appended -> {
                        if (appended.message().type() == TurnMessage.Type.TOOL_CALL) {
                            // The model's round is on the record now: the message that carries
                            // this call carries the thought before it too — also one that led to
                            // text first. History renders that thought, so a joiner must not get
                            // the live card on top of it.
                            sessionStreams.forgetCardsStartingWith(channelId,
                                    "task-think-" + sessionId.value() + "-");
                        }
                        drawToolMessage(appended.message(), null, liveView, liveTasks, openTaskNodeId, bus,
                                taskToSession);
                    }
                    default -> { }
                }
            }
        };
        // A sub-agent's session began a turn: its card, under the sub-agent that called it.
        java.util.function.BiConsumer<SessionId, String> onSubAgentStarted = (subSession, agentName) -> {
            synchronized (liveLock) {
                java.util.UUID taskId = taskOf(subSession);
                if (liveTasks.containsKey("sub-" + taskId)) {
                    return;
                }
                log.info("sub-agent started: {}", agentName);
                // One the chat's own turn called has no card above it.
                java.util.UUID caller = runtimeSessions.find(subSession)
                        .map(ai.mindconnect.agent.runtime.api.domain.Session::parentId)
                        .map(ChatUiController::taskOf).orElse(null);
                startSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        liveTasks.containsKey("sub-" + caller) ? caller : null,
                        taskId, agentName, subSession, "");
            }
        };
        java.util.function.BiConsumer<SessionId, StreamEvent> onSubAgentEvent =
                (subSession, event) -> {
            synchronized (liveLock) {
                closeThinking(thinkingFor(thinkings, sessionId, null), liveView, bus, false);
                java.util.UUID taskId = taskOf(subSession);
                LiveTask card = liveTasks.get("sub-" + taskId);
                if (card == null) {
                    return;
                }
                String agentName = card.name;
                // The sub-agent's own reasoning, in its nested list — same rule as
                // at the top level: anything but more thinking closes its card.
                String scope = scopeOf(taskId, taskToSession);
                LiveThinking thinking = thinkingFor(thinkings, sessionId, scope);
                if (!(event instanceof TurnStreamEvent.Thinking)) {
                    closeThinking(thinking, liveView, bus, false);
                }
                switch (event) {
                    case TurnStreamEvent.Thinking reasoning ->
                        onThinking(thinking, scope, reasoning.text(), liveView, bus);
                    case TurnStreamEvent.MessageWritten appended ->
                        drawToolMessage(appended.message(), taskId, liveView, liveTasks, openTaskNodeId, bus,
                                taskToSession);
                    case SessionStreamEvent.SubAgentDone ended -> {
                        log.info("sub-agent done: {}", agentName);
                        finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession, taskId,
                                agentName, ended.finalText() == null ? "" : ended.finalText(), null);
                    }
                    default -> { }
                }
            }
        };
        // Reviewers run after the answer is on screen. Without a card the chat
        // just sits there, which makes a finished-looking turn feel stuck.
        List<TaskCardComponent> reviewerVerdicts = new ArrayList<>();
        Consumer<SessionEvent> onReview = notice -> {
            String reviewer = String.valueOf(notice.details().get(ResponseReviewPlugin.REVIEWER));
            String node = "task-reviewer-" + sessionId.value() + "-" + turnKey + "-" + reviewer;
            synchronized (liveLock) {
                if (ResponseReviewPlugin.REVIEW_STARTED.equals(notice.kind())) {
                    publishPatch(bus, appendCard(liveView, null, TaskCardComponent.runningReviewer(node, reviewer)));
                } else if (ResponseReviewPlugin.REVIEW_FINISHED.equals(notice.kind())) {
                    var verdict = TaskCardComponent.doneReviewer(node, reviewer,
                            String.valueOf(notice.details().get(ResponseReviewPlugin.VERDICT)), null);
                    publishPatch(bus, liveView.streamTaskUpdate(verdict));
                    // Kept for after the turn: the list is rebuilt from the
                    // stored history then, and a review is not part of it.
                    reviewerVerdicts.add(verdict);
                }
            }
        };
        Consumer<TurnInterruptionEvent.ApprovalRequested> asked = approval -> {
            synchronized (liveLock) {
                closeThinking(thinkingFor(thinkings, sessionId, null), liveView, bus, false);
                // Durable already; this is the live mirror: push the card of the
                // tool call that waits for an approval into the open stream.
                publishPatch(bus, liveView.appendApprovalCard(
                        approvalCard(sessionId, approval.callId(), approval.toolName(), approval.arguments())));
            }
        };
        SessionId chat = sessionId;
        java.util.concurrent.CompletableFuture<String> answer = turnStarter.apply(event -> {
            boolean own = event.sessionId().equals(chat);
            switch (event.known()) {
                // Whoever asks, the card stands in this chat.
                case TurnInterruptionEvent.ApprovalRequested approval -> asked.accept(approval);
                case SessionStreamEvent.SubAgentStarted started when !own ->
                    onSubAgentStarted.accept(event.sessionId(), started.agentName());
                case SessionStreamEvent.Other other when own -> onReview.accept(event);
                // What a capability says about a sub-agent's session is not drawn here.
                case SessionStreamEvent.Other other -> { }
                case StreamEvent told when own -> onEvent.accept(told);
                case StreamEvent told -> onSubAgentEvent.accept(event.sessionId(), told);
            }
        });

        // 3. When the turn completes, replace placeholders with persisted
        //    final bot message + collapsed activity, then send the done event.
        answer.whenComplete((response, error) -> {
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
                    // A thought, tool or sub-agent the failure cut short must not keep
                    // running on screen: no result event comes for it any more.
                    synchronized (liveLock) {
                        for (LiveThinking open : thinkings.values()) closeThinking(open, liveView, bus, false);
                        failOpenTasks(liveView, liveTasks, bus, taskToSession,
                                cause instanceof java.util.concurrent.CancellationException
                                        ? "Cancelled by user before the tool finished"
                                        : "The turn failed before the tool finished: " + message);
                    }
                    publishPatch(bus, liveView.streamError(message, typingId));
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
                publishPatch(bus, finalView.streamDone(typingId));
                // After the rebuild, or it would wipe them. They sit below
                // the answer, which is also when they ran.
                synchronized (liveLock) {
                    for (TaskCardComponent verdict : reviewerVerdicts) {
                        publishPatch(bus, finalView.streamTaskStart(verdict));
                    }
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

    /**
     * The thinking card of the current stretch of reasoning in one scope
     * (the top-level agent, or one running sub-agent), while a turn streams.
     *
     * <p>A thought is long and arrives a few characters at a time, and the
     * body patch carries the cumulative text — so publishing one patch per
     * delta would send the text over and over. Deltas are folded and the
     * body goes out at most every {@link #PUBLISH_INTERVAL_MS}; the reader
     * cannot tell, and the closing patch always carries the whole thought.
     */
    private static final class LiveThinking {
        private static final long PUBLISH_INTERVAL_MS = 120;

        private final String scopeKey;
        private final String scope;
        private final String channelId;
        private final StringBuilder text = new StringBuilder();
        private String nodeId;
        private long startedAt;
        private long lastPublishedAt;

        LiveThinking(String scopeKey, String scope, String channelId) {
            this.scopeKey = scopeKey;
            this.scope = scope;
            this.channelId = channelId;
        }

        /** {@code null} for the top-level agent, else the sub-session id. */
        String scope() {
            return scope;
        }

        String channelId() {
            return channelId;
        }

        boolean open() {
            return nodeId != null;
        }

        String nodeId() {
            return nodeId;
        }

        String text() {
            return text.toString();
        }

        /** Opens a new card; returns its node id. */
        String start() {
            // Never reused: a count per turn restarted at 0 and collided with
            // the card a cancelled turn left on the page.
            nodeId = "task-think-" + scopeKey + "-" + liveNodeKey();
            startedAt = System.currentTimeMillis();
            lastPublishedAt = startedAt;
            text.setLength(0);
            return nodeId;
        }

        /** Adds a delta; true when enough has changed since the last patch to send another. */
        boolean append(String delta) {
            text.append(delta);
            long now = System.currentTimeMillis();
            if (now - lastPublishedAt < PUBLISH_INTERVAL_MS) return false;
            lastPublishedAt = now;
            return true;
        }

        /** The finished card, and the slot is free for the next stretch. */
        TaskCardComponent close() {
            var card = TaskCardComponent.doneThinking(nodeId, text.toString(),
                    System.currentTimeMillis() - startedAt);
            nodeId = null;
            return card;
        }
    }

    /** The live thinking of one scope — {@code null} for the top-level agent, else the sub-session id. */
    private static LiveThinking thinkingFor(java.util.Map<String, LiveThinking> thinkings,
                                            SessionId sessionId, String scope) {
        String key = scope == null ? sessionId.value() : scope;
        return thinkings.computeIfAbsent(key, k -> new LiveThinking(k, scope,
                ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId)));
    }

    /**
     * A reasoning delta: opens the scope's card on the first one, grows its body afterwards.
     *
     * <p>Nothing persisted holds a thought until what it led to is saved, so a
     * client that attaches meanwhile — back from another page — gets the card
     * from the catch-up frames, as it is now; the body REPLACEs that follow
     * would land nowhere without it.
     */
    private void onThinking(LiveThinking thinking, String scope, String text, ChatPage liveView,
                            ai.mindconnect.chatui.service.StreamBus bus) {
        if (!thinking.open()) {
            thinking.start();
            thinking.append(text);
            sessionStreams.rememberCard(thinking.channelId(), thinking.nodeId(), publishPatch(bus, appendCard(liveView, scope,
                    TaskCardComponent.runningThinking(thinking.nodeId(), thinking.text()))));
            return;
        }
        if (thinking.append(text)) {
            publishPatch(bus, liveView.streamThinking(thinking.nodeId(), thinking.text()));
            sessionStreams.rememberCard(thinking.channelId(), thinking.nodeId(), json(appendCard(liveView, scope,
                    TaskCardComponent.runningThinking(thinking.nodeId(), thinking.text()))));
        }
    }

    /**
     * Ends the open thinking card, if any: header flips from running to "thought for".
     *
     * @param keepForJoiners the thought led to the streaming answer, which is
     *                       saved — with the thought — only when the turn ends,
     *                       so a joiner still needs the finished card; a thought
     *                       that led to a tool call is saved with that call and
     *                       rendered from history, so the catch-up drops it
     */
    private void closeThinking(LiveThinking thinking, ChatPage liveView,
                               ai.mindconnect.chatui.service.StreamBus bus, boolean keepForJoiners) {
        if (!thinking.open()) return;
        String nodeId = thinking.nodeId();
        TaskCardComponent done = thinking.close();
        publishPatch(bus, liveView.streamTaskUpdate(done));
        if (keepForJoiners) {
            sessionStreams.rememberCard(thinking.channelId(), nodeId, json(appendCard(liveView, thinking.scope(), done)));
        } else {
            sessionStreams.forgetCard(thinking.channelId(), nodeId);
        }
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
        final long startedAt = System.currentTimeMillis();

        LiveTask(String nodeId, String name, boolean isSubAgent,
                 java.util.Map<String, Object> input, java.util.UUID parentTaskId) {
            this.nodeId = nodeId;
            this.name = name;
            this.isSubAgent = isSubAgent;
            this.input = input;
            this.parentTaskId = parentTaskId;
        }
    }

    /** A tool call or its result on the record, as its live card; any other message draws nothing. */
    private void drawToolMessage(TurnMessage message, java.util.UUID parentTaskId,
                                 ChatPage liveView,
                                 java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                 String[] openTaskNodeId,
                                 ai.mindconnect.chatui.service.StreamBus bus,
                                 java.util.Map<java.util.UUID, SessionId> taskToSession) {
        switch (message.type()) {
            // One card per call; a message with several calls announces the first,
            // the rest show with their results.
            case TOOL_CALL -> message.toolCalls().stream().findFirst().ifPresent(call -> {
                log.info("tool started: {}", call.name());
                startToolCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        parentTaskId, call.name(), call.arguments());
            });
            case TOOL_RESULT -> {
                String toolName = String.valueOf(message.metadata().getOrDefault("toolName", "tool"));
                boolean failed = Boolean.TRUE.equals(message.metadata().get("failed"));
                log.info("tool {}: {}", failed ? "failed" : "done", toolName);
                // The session the result is kept in: the sub-agent's for one of its calls, else the chat's.
                SessionId keptIn = parentTaskId == null ? liveView.sessionId() : taskToSession.get(parentTaskId);
                finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                        parentTaskId, toolName, message.text(), 0, failed,
                        ToolResultDownloadController.url(keptIn, message.id()));
            }
            default -> { }
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

    /**
     * Seeds the keys of live nodes. Started from the clock so a server that
     * restarts does not hand out the ids of the nodes a page still shows.
     */
    private static final java.util.concurrent.atomic.AtomicLong LIVE_NODE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    /** A short key no other live node in this JVM — this turn or an earlier one — has had. */
    private static String liveNodeKey() {
        return Long.toString(LIVE_NODE_SEQ.incrementAndGet(), 36);
    }

    private void startToolCard(ChatPage liveView,
                               java.util.LinkedHashMap<String, LiveTask> liveTasks,
                               String[] openTaskNodeId,
                               ai.mindconnect.chatui.service.StreamBus bus,
                               java.util.Map<java.util.UUID, SessionId> taskToSession,
                               java.util.UUID parentTaskId, String toolName, java.util.Map<String, Object> arguments) {
        String scope = scopeOf(parentTaskId, taskToSession);
        // A key of its own rather than the count of this turn's tools, which
        // restarted at 0 and collided with a card a cancelled turn left behind.
        String key  = "tool-" + (scope == null ? "top" : scope) + "-" + liveNodeKey() + "-" + toolName;
        String node = "task-" + key;
        liveTasks.put(key, new LiveTask(node, toolName, false, arguments, parentTaskId));
        openTaskNodeId[0] = null;
        var card = TaskCardComponent.runningTool(node, toolName, arguments, objectMapper);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishToolCard(ChatPage liveView,
                                java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                String[] openTaskNodeId,
                                ai.mindconnect.chatui.service.StreamBus bus,
                                java.util.UUID parentTaskId, String toolName,
                                String resultOrError, long durationMs, boolean failed,
                                String fullResultUrl) {
        LiveTask lt = findOpenTool(liveTasks, toolName, parentTaskId);
        if (lt == null) return;
        lt.output = resultOrError;
        lt.durationMs = durationMs;
        lt.done = true;
        var card = failed
                ? TaskCardComponent.failedTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs,
                        fullResultUrl, objectMapper)
                : TaskCardComponent.doneTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs,
                        fullResultUrl, objectMapper);
        publishPatch(bus, liveView.streamTaskUpdate(card));
        if (lt.nodeId.equals(openTaskNodeId[0])) openTaskNodeId[0] = null;
    }

    /**
     * Flips every card of the turn still showing "running" to failed, innermost
     * first. Called when the turn itself fails or is cancelled: the tool's own
     * result event never comes then — the cancel stub is written to the store,
     * not to the stream — and the card would spin until the page is reloaded.
     */
    private void failOpenTasks(ChatPage liveView,
                               java.util.LinkedHashMap<String, LiveTask> liveTasks,
                               ai.mindconnect.chatui.service.StreamBus bus,
                               java.util.Map<java.util.UUID, SessionId> taskToSession,
                               String reason) {
        var open = new java.util.ArrayList<>(liveTasks.entrySet());
        java.util.Collections.reverse(open);
        for (var e : open) {
            LiveTask lt = e.getValue();
            if (lt.done) continue;
            lt.done = true;
            long durationMs = System.currentTimeMillis() - lt.startedAt;
            if (lt.isSubAgent) {
                java.util.UUID taskId = java.util.UUID.fromString(e.getKey().substring("sub-".length()));
                SessionId subSession = taskToSession.get(taskId);
                String tid = subSession != null ? subSession.value() : taskId.toString();
                publishPatch(bus, liveView.streamSubAgentDone(
                        TaskCardComponent.failedSubAgentSummary(tid, lt.name),
                        TaskCardComponent.stackId(tid),
                        TaskCardComponent.subAgentAnswer(tid, reason)));
            } else {
                publishPatch(bus, liveView.streamTaskUpdate(
                        TaskCardComponent.failedTool(lt.nodeId, lt.name, lt.input, reason, durationMs, null, objectMapper)));
            }
        }
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
    /** The patch as the stream would carry it, without publishing it; {@code null} if it does not serialise. */
    private String json(UiPatch patch) {
        try {
            return objectMapper.writeValueAsString(patch);
        } catch (Exception e) {
            log.warn("Failed to serialise SSE patch", e);
            return null;
        }
    }

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
            return runtime.service(ai.mindconnect.agent.runtime.capabilities.memory.MemoryInspection.class)
                    .inspect(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load working memory for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }
}
