package ai.mindconnect.agent.starter.runtime.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.starter.runtime.engine.RuntimeEngineAutoConfiguration;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

/** An agent as the application stores it, with a tool of the earlier SPI, run by the runtime on the plugin kernel. */
class RuntimeBridgeAutoConfigurationTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));
    private static final AgentDefinition LIBRARIAN = new AgentDefinition(AgentId.random(), "librarian",
            "Finds books.", "You find books.", null, "small", 6, AutoCompactConfig.DEFAULT,
            AgentDefinitionStatus.ACTIVE,
            List.of(new AgentTool(AgentToolId.random(), "search_catalog", "Searches our catalog.",
                            Map.of("branch", "main"), true, false, false, null),
                    new AgentTool(AgentToolId.random(), "order_book", null, Map.of(), true, true, true, null),
                    new AgentTool(AgentToolId.random(), "switched_off", null, Map.of(), false, false, false, null)),
            List.of(), Instant.EPOCH, Instant.EPOCH);

    /** The earlier SPI: resolved for one agent's tool entry, and told on the thread who calls. */
    private static final ToolRegistry TOOLS = (entry, scope) -> Optional.of(new Tool() {
        @Override
        public String name() {
            return entry.name();
        }

        @Override
        public String description() {
            return entry.description();
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            return "found " + arguments.get("q") + " in " + entry.overrides().get("branch") + " for "
                    + ToolCallScope.current().map(calling -> calling.userId().value()).orElse("nobody");
        }
    });

    @TempDir
    Path dataDirectory;

    @Test
    void a_stored_agent_becomes_the_runtimes_with_what_it_set_for_its_tools() {
        var agent = new AgentDefinitionMapper().toRuntime(LIBRARIAN);

        assertThat(agent.name()).isEqualTo("librarian");
        assertThat(agent.instructions()).isEqualTo("You find books.");
        assertThat(agent.modelSettings().model()).isEqualTo("small");
        assertThat(agent.maxRounds()).isEqualTo(6);
        assertThat(agent.tools()).extracting(binding -> binding.name()).containsExactly("search_catalog", "order_book");
        assertThat(agent.binding("search_catalog").orElseThrow().metadata())
                .containsEntry("overrides", Map.of("branch", "main"))
                .containsEntry("description", "Searches our catalog.")
                .doesNotContainKey(PermissionPlugin.NEEDS_APPROVAL);
        assertThat(agent.binding("order_book").orElseThrow().deferred()).isTrue();
        assertThat(agent.binding("order_book").orElseThrow().metadata())
                .containsEntry(PermissionPlugin.NEEDS_APPROVAL, true);
        assertThat(agent.metadata()).containsEntry(AgentDefinitionMapper.AGENT_ID, LIBRARIAN.id().value())
                .containsKey(MemoryPlugin.MEMORY);
    }

    @Test
    void a_stored_agent_runs_a_turn_with_a_tool_of_the_earlier_spi_and_its_calls_are_traced() {
        List<LlmRequest> asked = new CopyOnWriteArrayList<>();
        LlmChat chat = (request, handler, cancellation, listener) -> {
            asked.add(request);
            if (asked.size() == 1) {
                handler.accept(new LlmStreamChunk.ToolCallDelta(0, "c1", "search_catalog", "{\"q\":\"owls\"}"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 10, 1));
            } else {
                handler.accept(new LlmStreamChunk.TextDelta("Found it."));
            }
            listener.onCall(new LlmCallEvent(Instant.EPOCH, 1, "small", "small", 10, 1, "stop", "{}", List.of(),
                    null, null, null));
        };
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(agents.findByName("librarian")).thenReturn(Optional.of(LIBRARIAN));
        LlmCallTraceRepository traces = mock(LlmCallTraceRepository.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RuntimeBridgeAutoConfiguration.class, RuntimeEngineAutoConfiguration.class))
                .withPropertyValues("mindconnect.data.base-dir=" + dataDirectory)
                .withBean(ScopeSupplier.class, () -> SCOPE)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().registerModule(new JavaTimeModule()))
                .withBean(TaskQueue.class, () -> new LocalTaskQueue(new InMemoryTaskStore()))
                .withBean(MessageRepository.class, () -> new InMemoryMessageRepository(SCOPE))
                .withBean(LlmChat.class, () -> chat)
                .withBean(LlmConfigRepository.class, () -> mock(LlmConfigRepository.class))
                .withBean(AgentDefinitionRepository.class, () -> agents)
                .withBean(SkillRepository.class, SkillRepository::empty)
                .withBean(ToolRegistry.class, () -> TOOLS)
                .withBean(ConversationSummaryRepository.class, () -> mock(ConversationSummaryRepository.class))
                .withBean(LlmCallTraceRepository.class, () -> traces)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Session session = context.getBean(SessionService.class).open(new SessionSpec(UserId.of("ada"),
                            null, context.getBean(AgentDefinitionMapper.class).toRuntime(LIBRARIAN), Map.of()));
                    TurnService turns = context.getBean(TurnService.class);

                    Turn ended = awaitEnd(turns, turns.submit(TurnRequest.text(session.id(), "Do we have owls?")));

                    assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
                    assertThat(context.getBean(SessionStore.class).messages(session.id()))
                            .filteredOn(message -> message.type() == TurnMessage.Type.TOOL_RESULT)
                            .extracting(TurnMessage::text).containsExactly("found owls in main for ada");
                    // The held-back tool is not offered; the agent's own description of the other is.
                    assertThat(asked.get(0).tools()).filteredOn(tool -> tool.name().equals("search_catalog"))
                            .extracting(tool -> tool.description()).containsExactly("Searches our catalog.");
                    assertThat(asked.get(0).tools()).extracting(tool -> tool.name()).doesNotContain("order_book");
                    verify(traces, times(2)).save(any());
                });
    }

    private static Turn awaitEnd(TurnService turns, TurnId turnId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Turn turn = turns.find(turnId).orElseThrow();
            if (turn.status() != TurnStatus.QUEUED && turn.status() != TurnStatus.RUNNING
                    && turn.status() != TurnStatus.WAITING) {
                return turn;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The turn did not end");
    }
}
