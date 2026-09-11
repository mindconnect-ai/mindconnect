package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceStore;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.agentrest.auth.SessionAccess;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The files an agent wrote, per workspace scope. A scope is a place, and the
 * three of them differ in how long what is written there lives:
 *
 * <ul>
 *   <li>{@code session} — the scratch space of one conversation</li>
 *   <li>{@code agent}   — what an agent remembers about one user across sessions</li>
 *   <li>{@code user}    — the user's own space, shared by every agent</li>
 * </ul>
 *
 * <p>The scope is in the path rather than a parameter because it decides which
 * identifiers the request even needs: a session workspace is addressed by its
 * session (agent and user follow from it), an agent workspace by agent and
 * user, and the user workspace by the user alone.
 *
 * <p>Every workspace read here is the caller's. A session workspace opens only
 * for the session's owner, and a path that names a user takes the caller's own
 * id or {@code me}; another user's workspace answers 404, like one that does
 * not exist.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceApiController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceApiController.class);

    /** One file in a workspace. Size is what a listing needs; content is a second call. */
    public record WorkspaceFile(String name, long size) {}

    private final WorkspaceStore store;
    private final SessionAccess sessionAccess;

    public WorkspaceApiController(WorkspaceStore store, SessionAccess sessionAccess) {
        this.store = store;
        this.sessionAccess = sessionAccess;
    }

    // ── Session scope ───────────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in a session's workspace",
            description = "The scratch space of one of the caller's conversations — what the agent "
                    + "wrote while answering. Agent and user are taken from the session.")
    @GetMapping("/session/{sessionId}/files")
    public List<WorkspaceFile> sessionFiles(@PathVariable String sessionId, @CurrentUser UserId caller) {
        return list(sessionScope(sessionId, caller));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from a session's workspace")
    @GetMapping(value = "/session/{sessionId}/files/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sessionFile(@PathVariable String sessionId,
                                              @PathVariable String name,
                                              @CurrentUser UserId caller) {
        return read(sessionScope(sessionId, caller), name);
    }

    // ── Agent + user scope ──────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in an agent's workspace for the caller",
            description = "What this agent keeps about the caller across conversations. The user "
                    + "in the path is the caller's own id or 'me'; any other user answers 404.")
    @GetMapping("/agent/{agentId}/user/{userId}/files")
    public List<WorkspaceFile> agentFiles(@PathVariable String agentId, @PathVariable String userId,
                                          @CurrentUser UserId caller) {
        return list(WorkspaceScope.agentUser(AgentId.of(agentId), UserPaths.requireSelf(userId, caller)));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from an agent's workspace for the caller")
    @GetMapping(value = "/agent/{agentId}/user/{userId}/files/{name}",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> agentFile(@PathVariable String agentId, @PathVariable String userId,
                                            @PathVariable String name, @CurrentUser UserId caller) {
        return read(WorkspaceScope.agentUser(AgentId.of(agentId), UserPaths.requireSelf(userId, caller)), name);
    }

    // ── User scope ──────────────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in the caller's own workspace",
            description = "The caller's space, shared by every agent that works for them. The user "
                    + "in the path is the caller's own id or 'me'; any other user answers 404.")
    @GetMapping("/user/{userId}/files")
    public List<WorkspaceFile> userFiles(@PathVariable String userId, @CurrentUser UserId caller) {
        return list(WorkspaceScope.user(UserPaths.requireSelf(userId, caller)));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from the caller's own workspace")
    @GetMapping(value = "/user/{userId}/files/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> userFile(@PathVariable String userId, @PathVariable String name,
                                           @CurrentUser UserId caller) {
        return read(WorkspaceScope.user(UserPaths.requireSelf(userId, caller)), name);
    }

    // ── The two things every scope does ─────────────────────────────────────

    private WorkspaceScope sessionScope(String sessionId, UserId caller) {
        AgentSession session = sessionAccess.requireOwned(SessionId.of(sessionId), caller);
        return WorkspaceScope.session(session.agentDefinitionId(), session.userId(), session.id());
    }

    private List<WorkspaceFile> list(WorkspaceScope scope) {
        List<WorkspaceFile> files = store.list(scope).stream()
                .map(name -> new WorkspaceFile(name, store.sizeOf(scope, name).orElse(0L)))
                .toList();
        log.info("GET workspace files scope={} → {} file(s)", scope.type(), files.size());
        return files;
    }

    private ResponseEntity<String> read(WorkspaceScope scope, String name) {
        return store.read(scope, name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
