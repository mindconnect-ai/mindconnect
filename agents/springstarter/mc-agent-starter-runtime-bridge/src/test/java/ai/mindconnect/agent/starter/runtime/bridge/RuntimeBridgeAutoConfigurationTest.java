package ai.mindconnect.agent.starter.runtime.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
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

    private static final Namespace NAMESPACE = new Namespace("test");

    /** A scope as a server's request filter binds it: per thread, the namespace and whoever is signed in. */
    private static final ScopeSupplier SCOPE = new ScopeSupplier() {
        private final ThreadLocal<Scope> bound = new ThreadLocal<>();

        @Override
        public Scope get() {
            return bound.get() == null ? Scope.of(NAMESPACE) : bound.get();
        }

        @Override
        public <T> T callIn(Scope scope, Callable<T> body) throws Exception {
            Scope before = bound.get();
            bound.set(scope);
            try {
                return body.call();
            } finally {
                bound.set(before);
            }
        }
    };
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
                    + ToolCallScope.current().map(calling -> calling.userId().value()).orElse("nobody")
                    + " as " + SCOPE.get().userIfAny().map(acting -> acting.value()).orElse("nobody");
        }
    });

    /** The chat page's sessions, as the application keeps them. */
    private final AgentSessionRepository chatSessions = mock(AgentSessionRepository.class);

    /** Where the chat page looks for the approvals a chat waits for. */
    private final ToolApprovalRepository openApprovals = mock(ToolApprovalRepository.class);

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
                .containsEntry(MemoryPlugin.MEMORY, Map.of("strategy", "compact", "maxConversationRatio",
                        AutoCompactConfig.DEFAULT.compactAtRatio(), "summaryPlacement", "user_message"))
                // Every skill there is: no entry.
                .doesNotContainKey(SkillPlugin.SKILLS);
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

        application(chat, agents, traces)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Session session = context.getBean(SessionService.class).open(new SessionSpec(UserId.of("ada"),
                            null, context.getBean(AgentDefinitionMapper.class).toRuntime(LIBRARIAN), Map.of()));
                    TurnService turns = context.getBean(TurnService.class);

                    // The session is Ada's; Eve is signed in and asks in it.
                    TurnId turnId = SCOPE.callIn(Scope.of(NAMESPACE, ai.mindconnect.agent.UserId.of("eve")),
                            () -> turns.submit(TurnRequest.text(session.id(), "Do we have owls?")));
                    Turn ended = awaitEnd(turns, turnId);

                    assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
                    assertThat(context.getBean(SessionStore.class).messages(session.id()))
                            .filteredOn(message -> message.type() == TurnMessage.Type.TOOL_RESULT)
                            .extracting(TurnMessage::text).containsExactly("found owls in main for ada as eve");
                    // The held-back tool is not offered; the agent's own description of the other is.
                    assertThat(asked.get(0).tools()).filteredOn(tool -> tool.name().equals("search_catalog"))
                            .extracting(tool -> tool.description()).containsExactly("Searches our catalog.");
                    assertThat(asked.get(0).tools()).extracting(tool -> tool.name()).doesNotContain("order_book");
                    verify(traces, times(2)).save(any());
                });
    }

    @Test
    void the_model_reads_the_instructions_with_everything_the_application_adds_in_its_order() throws Exception {
        AgentDefinition archivist = new AgentDefinition(AgentId.random(), "archivist", "Files things.",
                "You file for {{ user_id }} as {{ agent_name }} on {{ current_date }}."
                        + "{% if todo_list_md %}{{ todo_list_md }}{% endif %}",
                null, "small", 6, AutoCompactConfig.DEFAULT, AgentDefinitionStatus.ACTIVE,
                List.of(new AgentTool(AgentToolId.random(), "memory_write", null, Map.of(), true, false, false,
                        null)),
                List.of(), Instant.EPOCH, Instant.EPOCH);
        List<LlmRequest> asked = new CopyOnWriteArrayList<>();
        LlmChat chat = (request, handler, cancellation, listener) -> {
            asked.add(request);
            handler.accept(new LlmStreamChunk.TextDelta("Filed."));
        };
        java.nio.file.Files.writeString(dataDirectory.resolve("AGENTS.md"), "Build with Maven.");
        var memory = new ai.mindconnect.agent.tools.usermemory.UserMemoryService(
                new ai.mindconnect.agent.tools.usermemory.adapter.InMemoryUserMemoryRepository(SCOPE));
        memory.write(ai.mindconnect.agent.UserId.of("ada"), "role",
                ai.mindconnect.agent.tools.usermemory.MemoryType.USER, "Head of purchasing", "c", null);
        var todos = new ai.mindconnect.agent.tools.todo.TodoListService(
                new ai.mindconnect.agent.tools.todo.adapter.InMemoryTodoListRepository(SCOPE));

        application(chat, mock(AgentDefinitionRepository.class), mock(LlmCallTraceRepository.class))
                .withPropertyValues("mindconnect.agent.instructions.user-dir=off")
                .withBean(ai.mindconnect.agent.tools.usermemory.UserMemoryService.class, () -> memory)
                .withBean(ai.mindconnect.agent.tools.todo.TodoListService.class, () -> todos)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Session session = context.getBean(SessionService.class).open(new SessionSpec(UserId.of("ada"),
                            null, context.getBean(AgentDefinitionMapper.class).toRuntime(archivist),
                            Map.of("workingDir", dataDirectory.toString())));
                    todos.replace(ai.mindconnect.agent.SessionId.of(session.id().value()), List.of(
                            new ai.mindconnect.agent.tools.todo.TodoItem(java.util.UUID.randomUUID(), "Sort the shelf", "Sorting the shelf",
                                    ai.mindconnect.agent.tools.todo.TodoStatus.PENDING, 0)));
                    TurnService turns = context.getBean(TurnService.class);

                    awaitOver(turns, turns.submit(TurnRequest.text(session.id(), "File this.")));

                    String instructions = asked.get(0).messages().get(0).content();
                    assertThat(instructions).startsWith("You file for ada as archivist on "
                            + java.time.LocalDate.now() + ".\n\n## Current Todo List\n").contains("Sort the shelf");
                    assertThat(java.util.stream.Stream.of("## Current Todo List", "## Working directory",
                            "## Project instructions", "Build with Maven.", "## Memory", "Head of purchasing",
                            "## Date and time").map(instructions::indexOf).toList())
                            .doesNotContain(-1).isSorted();
                });
    }

    /** The application's beans as the starters find them, with this model and these stores. */
    private ApplicationContextRunner application(LlmChat chat, AgentDefinitionRepository agents,
                                                 LlmCallTraceRepository traces) {
        return new ApplicationContextRunner()
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
                .withBean(AgentSessionRepository.class, () -> chatSessions)
                .withBean(AgentChatService.class, () -> mock(AgentChatService.class))
                .withBean(ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository.class,
                        () -> mock(ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository.class))
                .withBean(ai.mindconnect.agent.tools.todo.TodoListRepository.class,
                        () -> mock(ai.mindconnect.agent.tools.todo.TodoListRepository.class))
                .withBean(ai.mindconnect.message.port.in.ConversationManager.class,
                        () -> mock(ai.mindconnect.message.port.in.ConversationManager.class))
                .withBean(ai.mindconnect.agent.chat.stream.SessionChannels.class,
                        ai.mindconnect.agent.chat.stream.SessionChannels::new)
                .withBean(ai.mindconnect.agent.chat.stream.UserChannels.class,
                        ai.mindconnect.agent.chat.stream.UserChannels::new)
                .withBean(ai.mindconnect.filestore.FileStore.class,
                        () -> mock(ai.mindconnect.filestore.FileStore.class))
                .withBean(ai.mindconnect.agent.management.directory.WorkingDirPolicy.class,
                        ai.mindconnect.agent.management.directory.WorkingDirPolicy::unrestricted)
                .withBean(ai.mindconnect.agent.management.directory.UserHome.class,
                        () -> ai.mindconnect.agent.management.directory.UserHome.of(null))
                .withBean(ToolApprovalRepository.class, () -> openApprovals);
    }

    /** A model that first calls the tool with the question's subject and then answers. */
    private static LlmChat callingThenAnswering(String toolName) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        return (request, handler, cancellation, listener) -> {
            if (calls.incrementAndGet() == 1) {
                handler.accept(new LlmStreamChunk.ToolCallDelta(0, "c1", toolName, "{\"q\":\"owls\"}"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 10, 1));
            } else {
                handler.accept(new LlmStreamChunk.TextDelta("Found it."));
            }
        };
    }

    /** A chat page's session, not yet kept anywhere. */
    private static AgentSession chatSession() {
        return new AgentSession(ai.mindconnect.agent.SessionId.random(), LIBRARIAN.id(),
                ai.mindconnect.agent.UserId.of("ada"), ConversationId.random(), "Books", SessionStatus.ACTIVE,
                Instant.EPOCH, null, null, null, null);
    }

    @Test
    void a_chats_turn_runs_on_the_new_runtime_and_is_written_where_the_chat_page_reads() {
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(agents.findById(LIBRARIAN.id())).thenReturn(Optional.of(LIBRARIAN));
        AgentSession chat = chatSession();
        when(chatSessions.findById(chat.id())).thenReturn(Optional.of(chat));

        application(callingThenAnswering("search_catalog"), agents, mock(LlmCallTraceRepository.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    keptInTheRuntimesStore(context, chat);
                    TurnService turns = context.getBean(TurnService.class);

                    TurnId turnId = turns.submit(TurnRequest.text(runtimeId(chat), "Do we have owls?"));

                    assertThat(awaitOver(turns, turnId).outcome().text()).isEqualTo("Found it.");
                    // Written into the conversation the chat page reads its history from.
                    assertThat(context.getBean(MessageRepository.class)
                            .findByConversation(chat.conversationId(), new PageRequest(0, 10)))
                            .extracting(Message::type).containsExactly(MessageType.CHAT, MessageType.TOOL_CALL,
                                    MessageType.TOOL_DISPATCHED, MessageType.TOOL_RESULT, MessageType.CHAT);
                });
    }

    @Test
    void a_tool_that_needs_approval_is_kept_as_a_card_and_goes_on_once_answered() {
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(agents.findById(LIBRARIAN.id())).thenReturn(Optional.of(LIBRARIAN));
        AgentSession chat = chatSession();
        when(chatSessions.findById(chat.id())).thenReturn(Optional.of(chat));

        application(callingThenAnswering("order_book"), agents, mock(LlmCallTraceRepository.class))
                .run(context -> {
                    keptInTheRuntimesStore(context, chat);
                    TurnService turns = context.getBean(TurnService.class);
                    TurnId turnId = turns.submit(TurnRequest.text(runtimeId(chat), "Order the owl book."));
                    Turn asked = awaitEnd(turns, turnId);
                    assertThat(asked.interruptions()).extracting(open -> open.call().toolName())
                            .containsExactly("order_book");
                    // The card is kept where the page draws it from after a reload.
                    verify(openApprovals).saveIfAbsent(argThat(open -> open.callId().equals("c1")
                            && open.rootSessionId().equals(chat.id())
                            && open.content().contains("\"name\":\"order_book\"")));

                    turns.resume(turnId, List.of(InterruptionAnswer.approve(asked.interruptions().get(0).id())));

                    assertThat(awaitOver(turns, turnId).outcome().text()).isEqualTo("Found it.");
                    verify(openApprovals).delete(chat.id(), "c1");
                });
    }

    @Test
    void a_turn_that_fails_is_over() {
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(agents.findById(LIBRARIAN.id())).thenReturn(Optional.of(LIBRARIAN));
        AgentSession chat = chatSession();
        when(chatSessions.findById(chat.id())).thenReturn(Optional.of(chat));
        LlmChat unreachable = (request, handler, cancellation, listener) -> {
            throw new IllegalStateException("The provider is down");
        };

        application(unreachable, agents, mock(LlmCallTraceRepository.class)).run(context -> {
            keptInTheRuntimesStore(context, chat);
            TurnService turns = context.getBean(TurnService.class);

            TurnId turnId = turns.submit(TurnRequest.text(runtimeId(chat), "Anyone there?"));

            assertThat(awaitOver(turns, turnId).status()).isEqualTo(TurnStatus.FAILED);
        });
    }

    private static ai.mindconnect.agent.runtime.api.domain.SessionId runtimeId(AgentSession chat) {
        return ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value());
    }

    /** The turn once nothing more will happen in it. */
    private static Turn awaitOver(TurnService turns, TurnId turnId) throws InterruptedException {
        Turn turn = turns.find(turnId).orElseThrow();
        for (int i = 0; i < 200 && !turn.status().isFinal(); i++) {
            Thread.sleep(25);
            turn = turns.find(turnId).orElseThrow();
        }
        return turn;
    }

    /** What opening a chat does in the application: its session goes into the runtime's store through the redirected repository. */
    private static void keptInTheRuntimesStore(org.springframework.context.ApplicationContext context,
                                               AgentSession chat) {
        new ManagedAgentSessionRepository(() -> context.getBean(SessionRepository.class),
                mock(AgentSessionRepository.class), context.getBean(ObjectMapper.class),
                () -> context.getBean(NamespaceProvider.class),
                written -> ManagedStoresFeature.agentOf(written, context.getBean(AgentDefinitionRepository.class),
                        context.getBean(EarlierAgentConverter.class))).create(chat);
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
