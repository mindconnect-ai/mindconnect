package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import ai.mindconnect.agentrest.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * What agents remember about the caller across chats — read and delete, for
 * the caller's own entries only. Writing stays with the agents' memory tools.
 * {@code agent} names an agent's own memory; left out, the call is about the
 * memory every agent of the caller shares.
 */
@Tag(name = "Memory", description = "What agents remember about you across chats: the memory all your "
        + "agents share, and what single agents keep for themselves. Read and delete your own entries; "
        + "agents write them with their memory tools.")
@RestController
@RequestMapping("/api/memories")
public class MemoryApiController {

    /** Null on a runtime without the memory: every call answers 404. */
    private final UserMemoryService memory;

    @Autowired
    public MemoryApiController(ObjectProvider<UserMemoryService> memory) {
        this(memory.getIfAvailable());
    }

    MemoryApiController(UserMemoryService memory) {
        this.memory = memory;
    }

    @Operation(summary = "List your memory entries",
            description = "Every entry agents keep about you — the shared memory and each agent's own "
                    + "(`agentId` set) — most recently changed first. `agent` narrows to one agent's own "
                    + "entries; `agent=shared` to the shared memory.")
    @GetMapping
    public List<MemoryEntry> list(@CurrentUser UserId caller,
                                  @Parameter(description = "An agent id, or `shared`")
                                  @RequestParam(value = "agent", required = false) String agent) {
        List<MemoryEntry> entries = service().list(caller);
        if (agent == null || agent.isBlank()) return entries;
        AgentId only = agentOf(agent);
        return entries.stream()
                .filter(e -> only == null ? e.shared() : only.equals(e.agentId()))
                .toList();
    }

    @Operation(summary = "Get one of your memory entries",
            description = "By name, in the shared memory or, with `agent`, in that agent's own. 404 when "
                    + "there is none.")
    @GetMapping("/{name}")
    public ResponseEntity<MemoryEntry> get(@CurrentUser UserId caller, @PathVariable("name") String name,
                                           @Parameter(description = "An agent id; leave out for the shared memory")
                                           @RequestParam(value = "agent", required = false) String agent) {
        return service().read(caller, agentOf(agent), name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete one of your memory entries",
            description = "By name, in the shared memory or, with `agent`, in that agent's own. The agents "
                    + "no longer know it from their next round on. 404 when there is none.")
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@CurrentUser UserId caller, @PathVariable("name") String name,
                                       @Parameter(description = "An agent id; leave out for the shared memory")
                                       @RequestParam(value = "agent", required = false) String agent) {
        return service().delete(caller, agentOf(agent), name)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** A name that normalises to nothing, or an over-long one. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
    }

    private UserMemoryService service() {
        if (memory == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This runtime keeps no memory");
        return memory;
    }

    /** {@code null}, blank and {@code shared} mean the shared memory. */
    private static AgentId agentOf(String agent) {
        if (agent == null || agent.isBlank() || "shared".equalsIgnoreCase(agent.strip())) return null;
        return AgentId.of(agent.strip());
    }
}
