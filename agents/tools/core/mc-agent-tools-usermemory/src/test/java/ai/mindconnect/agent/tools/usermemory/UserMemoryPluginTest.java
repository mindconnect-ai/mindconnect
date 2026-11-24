package ai.mindconnect.agent.tools.usermemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;

class UserMemoryPluginTest {

    private static final ai.mindconnect.agent.UserId ADA = ai.mindconnect.agent.UserId.of("ada");
    private static final String SECRETARY = "11111111-1111-1111-1111-111111111111";

    private final PluginRegistry registry = stored(new DefaultPluginRegistry()).install(new UserMemoryPlugin());
    private final UserMemoryService service = registry.get(UserMemoryService.class);

    private static PluginRegistry stored(PluginRegistry registry) {
        UserMemoryRepository memories = new MapUserMemoryRepository();
        registry.provide(UserMemoryRepository.class, () -> memories);
        return registry;
    }

    @Test
    void whoever_shows_the_memory_asks_the_runtime_for_it() {
        assertThat(registry.get(UserMemoryService.class)).isSameAs(service);
    }

    @Test
    void an_agent_without_a_memory_tool_reads_no_section() {
        service.write(ADA, "role", MemoryType.USER, "Head of purchasing", "c", null);

        assertThat(instructions(List.of(ToolBinding.of("bash")))).isEqualTo("You help.");
    }

    @Test
    void an_agent_that_writes_reads_the_entries_and_how_to_keep_them() {
        service.write(ADA, "role", MemoryType.USER, "Head of purchasing", "c", null);

        assertThat(instructions(List.of(ToolBinding.of(MemoryWriteTool.NAME))))
                .startsWith("You help.\n\n## Memory\n").contains("memory_write").contains("Head of purchasing");
    }

    @Test
    void an_agent_that_only_reads_is_told_so() {
        assertThat(instructions(List.of(ToolBinding.of(MemoryReadTool.NAME))))
                .contains("You can read it but not change it.").contains("No memories saved yet");
    }

    @Test
    void the_operators_scope_on_the_binding_decides_whose_entries_are_listed() {
        service.write(ADA, "role", MemoryType.USER, "Head of purchasing", "c", null);
        service.write(ADA, AgentId.of(SECRETARY), "calendar", MemoryType.USER, "Keeps Fridays free", "c", null);
        ToolBinding own = new ToolBinding(MemoryWriteTool.NAME, false,
                Map.of(UserMemoryPlugin.OVERRIDES, Map.of(MemoryReach.OVERRIDE, "agent")));

        assertThat(instructions(List.of(own))).contains("Keeps Fridays free").doesNotContain("Head of purchasing");
    }

    private String instructions(List<ToolBinding> tools) {
        AgentDefinition agent = new AgentDefinition("secretary", "Helps.", "You help.", ModelSettings.NONE, tools, 5,
                Map.of(UserMemoryPlugin.AGENT_ID, SECRETARY));
        Session session = new Session(SessionId.of("s1"), UserId.of("ada"), null, null, Map.of(), Instant.EPOCH);
        return registry.run(RuntimeHook.INSTRUCTIONS, new AgentContext(session, agent, TurnId.of("t1")),
                ignored -> "You help.");
    }
}
