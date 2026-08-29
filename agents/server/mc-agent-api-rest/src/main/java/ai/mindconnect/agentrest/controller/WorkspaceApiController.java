package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
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
import java.util.UUID;

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
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceApiController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceApiController.class);

    /** One file in a workspace. Size is what a listing needs; content is a second call. */
    public record WorkspaceFile(String name, long size) {}

    private final WorkspaceStore store;
    private final AgentSessionService sessionService;

    public WorkspaceApiController(WorkspaceStore store, AgentSessionService sessionService) {
        this.store = store;
        this.sessionService = sessionService;
    }

    // ── Session scope ───────────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in a session's workspace",
            description = "The scratch space of one conversation — what the agent wrote while "
                    + "answering. Agent and user are taken from the session.")
    @GetMapping("/session/{sessionId}/files")
    public List<WorkspaceFile> sessionFiles(@PathVariable UUID sessionId) {
        return list(sessionScope(sessionId));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from a session's workspace")
    @GetMapping(value = "/session/{sessionId}/files/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sessionFile(@PathVariable UUID sessionId,
                                              @PathVariable String name) {
        return read(sessionScope(sessionId), name);
    }

    // ── Agent + user scope ──────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in an agent's workspace for one user",
            description = "What this agent keeps about this user across conversations.")
    @GetMapping("/agent/{agentId}/user/{userId}/files")
    public List<WorkspaceFile> agentFiles(@PathVariable UUID agentId, @PathVariable String userId) {
        return list(WorkspaceScope.agentUser(agentId, userId));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from an agent's workspace")
    @GetMapping(value = "/agent/{agentId}/user/{userId}/files/{name}",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> agentFile(@PathVariable UUID agentId, @PathVariable String userId,
                                            @PathVariable String name) {
        return read(WorkspaceScope.agentUser(agentId, userId), name);
    }

    // ── User scope ──────────────────────────────────────────────────────────

    @Operation(tags = "Workspaces", summary = "Files in a user's own workspace",
            description = "The user's space, shared by every agent that works for them.")
    @GetMapping("/user/{userId}/files")
    public List<WorkspaceFile> userFiles(@PathVariable String userId) {
        return list(WorkspaceScope.user(userId));
    }

    @Operation(tags = "Workspaces", summary = "Read a file from a user's workspace")
    @GetMapping(value = "/user/{userId}/files/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> userFile(@PathVariable String userId, @PathVariable String name) {
        return read(WorkspaceScope.user(userId), name);
    }

    // ── The two things every scope does ─────────────────────────────────────

    private WorkspaceScope sessionScope(UUID sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
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
