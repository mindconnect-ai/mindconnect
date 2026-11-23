package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.tools.usermemory.MemoryEntry;
import ai.mindconnect.agent.tools.usermemory.MemoryReach;
import ai.mindconnect.agent.tools.usermemory.MemoryReadTool;
import ai.mindconnect.agent.tools.usermemory.MemoryWriteTool;
import ai.mindconnect.agent.tools.usermemory.UserMemoryService;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.prompt.PromptSection;

import java.util.List;
import java.util.Optional;

/**
 * The "Memory" section of the system prompt: how to use the memory, and one
 * line per entry — the content stays out until the model reads it. Only for
 * an agent that has a memory tool enabled: {@code memory_write} makes the
 * memory read-write, {@code memory_read} alone read-only, and an agent with
 * neither — the stateless helpers, a reviewer — sees no section at all.
 * Which entries are listed — the user's own, this agent's, or both — is the
 * {@link MemoryReach} of the write tool's binding (else the read tool's).
 */
public final class MemoryIndex implements PromptSection {

    /** The tool group all memory tools are filed under. */
    public static final String TOOL_GROUP = MemoryReadTool.GROUP;

    /** Most entries listed; past it the model is pointed to {@code memory_read}. */
    static final int MAX_LINES = ai.mindconnect.agent.tools.usermemory.MemoryIndex.MAX_LINES;

    private final ai.mindconnect.agent.tools.usermemory.MemoryIndex index;

    public MemoryIndex(UserMemoryService service) {
        this.index = new ai.mindconnect.agent.tools.usermemory.MemoryIndex(service);
    }

    @Override
    public String render(AgentDefinition def, AgentSession session) {
        if (session == null || session.userId() == null) return "";
        Optional<AgentTool> writeTool = binding(def, MemoryWriteTool.NAME);
        Optional<AgentTool> readTool = binding(def, MemoryReadTool.NAME);
        boolean writes = writeTool.isPresent();
        if (!writes && readTool.isEmpty()) return "";
        // The write tool's setting decides when there is one: it is the tool that changes the memory.
        MemoryReach reach = MemoryReach.of(writeTool.or(() -> readTool).orElseThrow());
        return index.render(session.userId(), def.id(), reach, writes);
    }

    private static Optional<AgentTool> binding(AgentDefinition def, String name) {
        if (def == null || def.tools() == null) return Optional.empty();
        return def.tools().stream().filter(t -> t.enabled() && name.equals(t.name())).findFirst();
    }
}
