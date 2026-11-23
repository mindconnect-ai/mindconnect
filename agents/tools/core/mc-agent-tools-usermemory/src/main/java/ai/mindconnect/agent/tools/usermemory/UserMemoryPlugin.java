package ai.mindconnect.agent.tools.usermemory;

import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * What agents remember of a user, in the runtime: whoever shows or edits it
 * asks the runtime for the {@link UserMemoryService}, and an agent that has
 * a memory tool reads the index of its entries in its instructions —
 * read-write with {@code memory_write}, read-only with {@code memory_read}
 * alone, and no section at all with neither.
 */
public final class UserMemoryPlugin implements Plugin {

    /**
     * The key in an agent's metadata of the id its own memory is filed
     * under; an agent without one has no memory of its own to list.
     */
    public static final String AGENT_ID = "id";

    /** The key in a tool binding's metadata of what the operator set for the tool; see {@link MemoryReach#OVERRIDE}. */
    public static final String OVERRIDES = "overrides";

    private final UserMemoryService service;

    public UserMemoryPlugin(UserMemoryService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "user-memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(UserMemoryService.class, () -> service);
        MemoryIndex index = new MemoryIndex(service);
        registry.after(RuntimeHook.INSTRUCTIONS, (context, instructions) -> instructions + section(index, context));
    }

    private static String section(MemoryIndex index, AgentContext context) {
        AgentDefinition agent = context.agent();
        Optional<ToolBinding> write = agent.binding(MemoryWriteTool.NAME);
        Optional<ToolBinding> read = agent.binding(MemoryReadTool.NAME);
        if (context.session().userId() == null || (write.isEmpty() && read.isEmpty())) {
            return "";
        }
        // The write tool's setting decides when there is one: it is the tool that changes the memory.
        MemoryReach reach = MemoryReach.of(write.or(() -> read).orElseThrow().metadata().get(OVERRIDES)
                instanceof Map<?, ?> overrides ? (Map<String, ?>) overrides : null);
        return index.render(UserId.of(context.session().userId().value()),
                agent.metadata().get(AGENT_ID) instanceof String id ? AgentId.of(id) : null, reach,
                write.isPresent());
    }
}
