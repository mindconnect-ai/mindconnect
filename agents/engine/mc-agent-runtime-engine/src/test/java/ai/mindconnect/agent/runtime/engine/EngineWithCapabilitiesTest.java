package ai.mindconnect.agent.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ai.mindconnect.agent.runtime.capabilities.guard.GuardResolver;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuardPlugin;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuardPlugin;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.InMemoryApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.InMemoryToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolSearchPlugin;
import ai.mindconnect.agent.runtime.capabilities.trace.TracePlugin;
import ai.mindconnect.agent.runtime.capabilities.trace.TraceRecord;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.store.InMemorySessionStorePlugin;
import ai.mindconnect.agent.runtime.prompt.PebblePromptPlugin;
import ai.mindconnect.agent.runtime.engine.service.EnginePlugin;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import java.util.Optional;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuard;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuard;
import org.junit.jupiter.api.Test;

/**
 * The engine and the capabilities put together the way a program embedding
 * the runtime would, with a model that reads from a script. Shows that the
 * two modules, which do not know each other, make one runtime through the
 * API's ports and hook points.
 */
class EngineWithCapabilitiesTest {

    /** Answers from a script and remembers what it was asked. */
    private static final class ScriptedGateway implements ModelGateway {
        private final Deque<TurnMessage> script = new ArrayDeque<>();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public synchronized LlmAnswer ask(ModelRequest request, TurnEventListener events) {
            requests.add(request);
            // Past the script's end it is the title that is asked for, after a turn the test did not follow.
            TurnMessage next = script.isEmpty() ? TurnMessage.assistant("Untitled") : script.pop();
            return LlmAnswer.of(List.of(next), new Usage(100, 10));
        }

        @Override
        public void cancel(TurnId turnId) {
        }
    }

    private static final AgentDefinition RESEARCHER = new AgentDefinition("researcher", "Looks things up.",
            "You research.", ModelSettings.ofModel("small"), List.of(), 5, Map.of());
    private static final AgentDefinition LEAD = new AgentDefinition("lead", "Leads.", "You lead.",
            ModelSettings.ofModel("big"),
            List.of(ToolBinding.of("bash"), ToolBinding.of("ask_user"), ToolBinding.deferred("read_pdf")),
            10, Map.of(SubAgentPlugin.SUB_AGENTS, List.of("researcher")));

    private final ScriptedGateway model = new ScriptedGateway();
    private final List<TraceRecord> traces = new CopyOnWriteArrayList<>();
    private final LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());
    private final PluginRegistry registry = new DefaultPluginRegistry();
    private SessionService sessions;
    private TurnService turns;
    private Session session;

    @BeforeEach
    void assemble_the_runtime() {
        registry.install(new Plugin() {
            @Override
            public String name() {
                return "namespace-fixed";
            }

            @Override
            public void install(PluginRegistry into) {
                into.provide(ScopeSupplier.class, () -> ScopeSupplier.fixed(new Namespace("test")));
            }
        });
        registry.install(new InMemorySessionStorePlugin());
        registry.install(new PebblePromptPlugin());
        registry.install(new Plugin() {
            @Override
            public String name() {
                return "model-scripted";
            }

            @Override
            public void install(PluginRegistry into) {
                into.provide(ModelGateway.class, () -> model);
            }
        });
        registry.install(new EnginePlugin(queue, new InMemorySharedStateStore()));
        registry.contribute(AgentResolver.class, AgentResolver.of(List.of(LEAD, RESEARCHER)));
        registry.contribute(SkillResolver.class, skills(
                new SkillDefinition("citations", "How to cite sources.", "Cite like this.", List.of())));
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(tool("bash"), tool("read_pdf"))));
        registry.install(new SkillPlugin());
        registry.install(new ToolSearchPlugin(new InMemoryToolActivation(ScopeSupplier.fixed(new Namespace("test")))));
        registry.install(new SubAgentPlugin(key -> true, SubAgentPlugin.DEFAULT_MAX_DEPTH));
        registry.install(new PermissionPlugin(PermissionEffect.ALLOW, new InMemoryApprovalMemory(ScopeSupplier.fixed(new Namespace("test")))));
        registry.install(new TracePlugin(traces::add));
        registry.install(new InputGuardPlugin());
        registry.install(new OutputGuardPlugin());
        registry.install(new ResponseReviewPlugin());
        registry.install(new SessionTitlePlugin());
        registry.contribute(PermissionRuleSource.class, any -> List.of(
                new PermissionRule("bash", PermissionEffect.ASK)));
        registry.start();
        sessions = registry.get(SessionService.class);
        turns = registry.get(TurnService.class);
        session = sessions.open(new SessionSpec(UserId.of("ada"), null, LEAD, Map.of()));
    }

    @AfterEach
    void stop() {
        queue.close();
        registry.close();
    }

    @Test
    void a_turn_answers_and_the_model_was_offered_what_the_capabilities_decided() throws InterruptedException {
        model.script.add(TurnMessage.assistant("Hello."));

        Turn turn = awaitRest(turns.submit(TurnRequest.text(session.id(), "Hi")));

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(turn.outcome().text()).isEqualTo("Hello.");
        ModelRequest asked = model.requests.get(0);
        assertThat(asked.instructions()).startsWith("You lead.").contains("citations: How to cite sources.");
        assertThat(asked.tools()).extracting(ToolDefinition::name).containsExactlyInAnyOrder(
                "bash", "ask_user", "skill", "tool_search", "run_agent", "run_agents", "list_agents");
        assertThat(traces).extracting(TraceRecord::kind).containsExactly(TraceRecord.MODEL_CALL);
    }

    @Test
    void a_tool_that_needs_approval_interrupts_the_turn_and_runs_once_approved() throws InterruptedException {
        model.script.add(call("c1", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Done."));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Run it"));
        Turn interrupted = awaitRest(turnId);
        assertThat(interrupted.status()).isEqualTo(TurnStatus.INTERRUPTED);
        Interruption interruption = interrupted.interruptions().get(0);
        assertThat(interruption.kind()).isEqualTo(Interruption.TOOL_APPROVAL);

        turns.resume(turnId, List.of(InterruptionAnswer.approve(interruption.id())));
        Turn ended = awaitRest(turnId);

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(toolResults(turnId)).containsExactly("ran bash");
    }

    @Test
    void the_sessions_running_turn_is_the_one_that_has_not_ended() throws InterruptedException {
        model.script.add(call("c1", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Done."));
        assertThat(turns.runningTurn(session.id())).isEmpty();

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Run it"));
        Turn interrupted = awaitRest(turnId);
        // A turn that waits for an answer has not ended, and one lined up behind it is not the running one.
        TurnId behind = turns.submit(TurnRequest.text(session.id(), "And then?"));
        assertThat(turns.runningTurn(session.id())).map(Turn::id).contains(turnId);

        model.script.add(TurnMessage.assistant("Nothing."));
        turns.resume(turnId, List.of(InterruptionAnswer.approve(interrupted.interruptions().get(0).id())));
        awaitRest(turnId);
        awaitRest(behind);

        assertThat(turns.runningTurn(session.id())).isEmpty();
    }

    @Test
    void a_sub_agents_turn_names_the_turn_that_called_it() throws InterruptedException {
        model.script.add(call("c1", "run_agent", Map.of("name", "researcher", "message", "Find the capital of France.")));
        model.script.add(call("c2", "bash", Map.of()));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Capital of France?"));
        Turn interrupted = awaitRest(turnId);
        TurnId subAgent = interrupted.interruptions().get(0).turnId();

        assertThat(interrupted.parent()).isNull();
        assertThat(turns.find(subAgent)).map(Turn::parent).contains(turnId);
        turns.cancel(turnId);
    }

    @Test
    void a_cancelled_turn_leaves_no_call_unanswered_and_the_session_takes_the_next_turn() throws InterruptedException {
        model.script.add(call("c1", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Five."));

        TurnId cancelled = turns.submit(TurnRequest.text(session.id(), "Run it"));
        awaitRest(cancelled);
        turns.cancel(cancelled);

        assertThat(toolResults(cancelled)).singleElement().asString().contains("Cancelled by user");
        TurnId next = turns.submit(TurnRequest.text(session.id(), "2 + 3?"));
        assertThat(awaitRest(next).status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void a_rejected_approval_is_an_error_the_model_reads() throws InterruptedException {
        model.script.add(call("c1", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Then not."));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Run it"));
        Interruption interruption = awaitRest(turnId).interruptions().get(0);
        turns.resume(turnId, List.of(InterruptionAnswer.reject(interruption.id(), "too risky")));
        Turn ended = awaitRest(turnId);

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(toolResults(turnId)).singleElement().asString().contains("did not approve", "too risky");
    }

    @Test
    void a_question_to_the_user_interrupts_and_the_answer_is_the_tools_result() throws InterruptedException {
        model.script.add(call("c1", "ask_user", Map.of("question", "Which city?")));
        model.script.add(TurnMessage.assistant("Berlin it is."));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Weather?"));
        Interruption interruption = awaitRest(turnId).interruptions().get(0);
        assertThat(interruption.kind()).isEqualTo(Interruption.USER_INPUT);
        turns.resume(turnId, List.of(InterruptionAnswer.text(interruption.id(), "Berlin")));
        Turn ended = awaitRest(turnId);

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(toolResults(turnId)).containsExactly("Berlin");
    }

    @Test
    void instructions_are_a_template_rendered_with_what_plugins_contribute() throws InterruptedException {
        registry.contribute(PromptVariableProvider.class, (agent, in) -> Map.of("user", in.userId().value()));
        AgentDefinition greeter = new AgentDefinition("greeter", "Greets.",
                "You talk to {{ user }}.{% if missing %} Never shown.{% endif %}", ModelSettings.NONE, List.of(), 5, Map.of());
        model.script.add(TurnMessage.assistant("Hello."));

        awaitRest(turns.submit(TurnRequest.text(sessions.changeAgent(session.id(), greeter).id(), "Hi")));

        assertThat(model.requests.get(0).instructions()).startsWith("You talk to ada.").doesNotContain("Never shown");
    }

    @Test
    void a_listener_a_plugin_contributes_hears_sessions_it_did_not_subscribe_to() throws InterruptedException {
        List<ai.mindconnect.agent.runtime.api.domain.SessionEvent> heard =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.contribute(ai.mindconnect.agent.runtime.api.port.in.SessionEventListener.class, heard::add);
        model.script.add(TurnMessage.assistant("Hello."));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Hi"));
        awaitRest(turnId);

        assertThat(heard).anyMatch(event -> event.is(StreamEvent.ASKING_LLM))
                .allMatch(event -> event.sessionId().equals(session.id()));
    }

    @Test
    void whoever_follows_a_session_hears_what_its_turns_report() throws InterruptedException {
        List<ai.mindconnect.agent.runtime.api.domain.SessionEvent> heard =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.get(ai.mindconnect.agent.runtime.api.port.in.SessionService.class).stream(session.id(), false, heard::add);
        model.script.add(call("c1", "bash", Map.of()));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Run it"));
        awaitRest(turnId);

        // The one stream tells all of it: the turn begins, calls a tool and stops to ask.
        assertThat(heard).extracting(SessionEvent::kind)
                .containsSubsequence("asking_llm", "message", "tool_call_started", "approval_requested");
        assertThat(heard).filteredOn(event -> event.is(StreamEvent.TOOL_CALL_STARTED)).singleElement()
                .satisfies(event -> assertThat(event.as(StreamEvent.TOOL_CALL_STARTED).orElseThrow())
                        .isEqualTo(new StreamEvent.ToolCallStarted("c1", "bash", Map.of(), 0)));
        // A turn that waits has not finished.
        assertThat(heard).noneMatch(event -> event.is(StreamEvent.DONE) || event.is(StreamEvent.ERROR));
        turns.cancel(turnId);
    }

    @Test
    void whoever_follows_a_turn_is_told_its_end_also_when_coming_late() throws InterruptedException {
        model.script.add(TurnMessage.assistant("Hello."));
        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Hi"));
        awaitRest(turnId);
        List<SessionEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>();

        turns.subscribe(turnId, heard::add);

        for (int i = 0; i < 200 && heard.stream().noneMatch(event -> event.is(StreamEvent.DONE)); i++) {
            Thread.sleep(10);
        }
        // The end comes once and last, whatever of the turn the channel still keeps.
        assertThat(heard).filteredOn(event -> event.is(StreamEvent.DONE)).singleElement()
                .satisfies(event -> assertThat(event.as(StreamEvent.DONE).orElseThrow())
                        .isEqualTo(new StreamEvent.Done(TurnStatus.COMPLETED, "Hello.", null)));
        assertThat(heard.get(heard.size() - 1).kind()).isEqualTo("done");
    }

    @Test
    void a_sub_agent_runs_a_turn_of_its_own_and_its_answer_is_the_tools_result() throws InterruptedException {
        model.script.add(call("c1", "run_agent", Map.of("name", "researcher", "message", "Find the capital of France.")));
        model.script.add(TurnMessage.assistant("Paris."));
        model.script.add(TurnMessage.assistant("The capital is Paris."));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Capital of France?"));
        Turn ended = awaitRest(turnId);

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(toolResults(turnId)).containsExactly("Paris.");
        ModelRequest subAgentCall = model.requests.get(1);
        assertThat(subAgentCall.instructions()).startsWith("You research.");
        assertThat(subAgentCall.settings().model()).isEqualTo("small");
        assertThat(subAgentCall.messages()).extracting(TurnMessage::text)
                .containsExactly("Find the capital of France.");
        // Its session stands under the caller's and names the call that opened it.
        Session called = sessions.find(turns.find(subAgentCall.turnId()).orElseThrow().sessionId()).orElseThrow();
        assertThat(called.parentId()).isEqualTo(session.id());
        assertThat(called.metadata()).containsEntry(SubAgentPlugin.OPENED_BY_CALL, "c1");
    }

    @Test
    void a_sub_agent_as_deep_as_sub_agents_may_go_calls_nobody() {
        ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));
        // The chat, and below it sub-agents' sessions one under the other.
        java.util.List<Session> chain = new java.util.ArrayList<>(List.of(session));
        for (int depth = 1; depth <= SubAgentPlugin.DEFAULT_MAX_DEPTH; depth++) {
            chain.add(sessions.open(new SessionSpec(UserId.of("ada"), chain.get(depth - 1).id(), LEAD, Map.of())));
        }
        Session deepest = chain.get(SubAgentPlugin.DEFAULT_MAX_DEPTH);
        Session above = chain.get(SubAgentPlugin.DEFAULT_MAX_DEPTH - 1);
        Map<String, Object> task = Map.of("name", "researcher", "message", "Find the capital of France.");

        assertThat(tools.resolve("run_agent", LEAD, deepest).orElseThrow().execute(
                new ToolCall("c1", "run_agent", task, TurnId.of("t1"), deepest.id()), deepest))
                .isEqualTo(ToolOutput.failed("Error: Sub-agent depth limit (5) exceeded"));
        assertThat(tools.resolve("run_agents", LEAD, deepest).orElseThrow().execute(
                new ToolCall("c2", "run_agents", Map.of("tasks", List.of(task)), TurnId.of("t1"), deepest.id()),
                deepest)).isEqualTo(ToolOutput.failed("Error: Sub-agent depth limit (5) exceeded"));
        assertThat(tools.resolve("run_agent", LEAD, above).orElseThrow().execute(
                new ToolCall("c3", "run_agent", task, TurnId.of("t1"), above.id()), above))
                .isInstanceOf(ToolOutput.Delegated.class);
    }

    @Test
    void several_sub_agents_run_side_by_side_and_their_answers_are_the_tools_result_together()
            throws InterruptedException {
        model.script.add(call("c1", "run_agents", Map.of("tasks", List.of(
                Map.of("name", "researcher", "message", "Capital of France?"),
                Map.of("name", "researcher", "message", "Capital of France, again?")))));
        // Both sub-agents answer the same, so it does not matter which one asks first.
        model.script.add(TurnMessage.assistant("Paris."));
        model.script.add(TurnMessage.assistant("Paris."));
        model.script.add(TurnMessage.assistant("Both say Paris."));

        Turn ended = awaitRest(turns.submit(TurnRequest.text(session.id(), "Ask twice.")));

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(toolResults(ended.id()))
                .containsExactly("## 1. researcher\nParis.\n\n## 2. researcher\nParis.");
        assertThat(ended.outcome().text()).isEqualTo("Both say Paris.");
    }

    @Test
    void an_approval_a_sub_agent_needs_comes_back_on_the_callers_turn() throws InterruptedException {
        registry.contribute(AgentResolver.class, (name, in) ->
                "operator".equals(name) ? Optional.of(new AgentDefinition("operator", "Runs commands.",
                        "You operate.", ModelSettings.NONE, ToolBinding.allOf("bash"), 5, Map.of()))
                        : Optional.empty());
        AgentDefinition boss = new AgentDefinition("boss", "Delegates.", "You delegate.", ModelSettings.NONE,
                List.of(), 5, Map.of(SubAgentPlugin.SUB_AGENTS, List.of("operator")));
        model.script.add(call("c1", "run_agent", Map.of("name", "operator", "message", "Run it.")));
        model.script.add(call("c2", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Ran."));
        model.script.add(TurnMessage.assistant("The operator ran it."));

        TurnId turnId = turns.submit(TurnRequest.text(sessions.changeAgent(session.id(), boss).id(), "Go"));
        Turn interrupted = awaitRest(turnId);
        assertThat(interrupted.status()).isEqualTo(TurnStatus.INTERRUPTED);
        Interruption interruption = interrupted.interruptions().get(0);
        assertThat(interruption.turnId()).isNotEqualTo(turnId);

        turns.resume(turnId, List.of(InterruptionAnswer.approve(interruption.id())));
        Turn ended = awaitRest(turnId);

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(ended.outcome().text()).isEqualTo("The operator ran it.");
    }

    @Test
    void a_session_gets_a_title_in_the_background_and_its_listeners_hear_of_it() throws InterruptedException {
        List<SessionEvent> heard = new CopyOnWriteArrayList<>();
        sessions.stream(session.id(), false, heard::add);
        model.script.add(TurnMessage.assistant("Hello."));
        model.script.add(TurnMessage.assistant("A greeting"));

        awaitRest(turns.submit(TurnRequest.text(session.id(), "Hi")));
        long deadline = System.currentTimeMillis() + 5_000;
        while (heard.stream().noneMatch(event -> event.kind().equals("session.title"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(heard).filteredOn(event -> event.kind().equals("session.title")).singleElement()
                .isEqualTo(new SessionEvent("session.title", session.id(), null, null,
                        Map.of("title", "A greeting")));
        assertThat(sessions.find(session.id()).orElseThrow().metadata()).containsEntry("title", "A greeting");
        // Asked of the session agent's own model: the assembly names none for such tasks.
        assertThat(model.requests.get(model.requests.size() - 1).settings()).isEqualTo(LEAD.modelSettings());
        assertThat(heard).first().matches(event -> event.is(StreamEvent.ASKING_LLM));
        assertThat(heard).anyMatch(event -> event.is(StreamEvent.DONE));
    }

    @Test
    void a_title_is_found_by_the_model_the_assembly_names_for_such_tasks() throws InterruptedException {
        registry.provide(ai.mindconnect.agent.runtime.api.domain.BackgroundModel.class,
                () -> ai.mindconnect.agent.runtime.api.domain.BackgroundModel.named("small"));
        model.script.add(TurnMessage.assistant("Hello."));
        model.script.add(TurnMessage.assistant("A greeting"));

        awaitRest(turns.submit(TurnRequest.text(session.id(), "Hi")));
        long deadline = System.currentTimeMillis() + 5_000;
        while (!sessions.find(session.id()).orElseThrow().metadata().containsKey("title")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sessions.find(session.id()).orElseThrow().metadata()).containsEntry("title", "A greeting");
        assertThat(model.requests.get(model.requests.size() - 1).settings().model()).isEqualTo("small");
    }

    @Test
    void an_input_guard_refuses_a_turn_before_it_exists() {
        registry.contribute(GuardResolver.class, new GuardResolver() {
            @Override
            public List<InputGuard> inputGuards(Session in) {
                return List.of(input -> new TurnAdmission.Refused("not here"));
            }
        });

        assertThatThrownBy(() -> turns.submit(TurnRequest.text(session.id(), "Hi")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not here");
        assertThat(model.requests).isEmpty();
    }

    @Test
    void an_output_guard_replaces_the_answer_the_session_keeps() throws InterruptedException {
        registry.contribute(GuardResolver.class, new GuardResolver() {
            @Override
            public List<OutputGuard> outputGuards(Session in) {
                return List.of((context, answer) ->
                        TurnMessage.assistant(answer.text() + " This is not investment advice."));
            }
        });
        model.script.add(TurnMessage.assistant("Buy."));

        Turn ended = awaitRest(turns.submit(TurnRequest.text(session.id(), "What now?")));

        assertThat(ended.outcome().text()).isEqualTo("Buy. This is not investment advice.");
        assertThat(turns.messages(ended.id())).last().extracting(TurnMessage::text)
                .isEqualTo("Buy. This is not investment advice.");
    }

    @Test
    void reviewers_read_the_answer_one_after_the_other_and_the_session_keeps_what_they_left()
            throws InterruptedException {
        AgentDefinition advisor = new AgentDefinition("advisor", "Advises.", "You advise.",
                ModelSettings.ofModel("big"), List.of(), 5,
                Map.of(ResponseReviewPlugin.REVIEWERS, List.of("lenient", "missing", "editor")));
        registry.contribute(AgentResolver.class, AgentResolver.of(List.of(
                new AgentDefinition("lenient", "Lets things pass.", "Pass what is fine.",
                        ModelSettings.ofModel("small"), List.of(), 1, Map.of()),
                new AgentDefinition("editor", "Adds the disclaimer.",
                        "The user asked: {{ user_message }}. The draft: {{ agent_response }}.",
                        ModelSettings.ofModel("small"), List.of(), 1, Map.of()))));
        model.script.add(TurnMessage.assistant("Buy."));
        model.script.add(TurnMessage.assistant("`PASS`"));
        model.script.add(TurnMessage.assistant("Buy. This is not investment advice."));
        List<SessionEvent> heard = new CopyOnWriteArrayList<>();
        sessions.stream(session.id(), false, heard::add);

        Turn ended = awaitRest(turns.submit(TurnRequest.text(sessions.changeAgent(session.id(), advisor).id(), "What now?")));

        assertThat(ended.outcome().text()).isEqualTo("Buy. This is not investment advice.");
        assertThat(turns.messages(ended.id())).last().extracting(TurnMessage::text)
                .isEqualTo("Buy. This is not investment advice.");
        // The reviewer reads the question and the draft in its instructions, and is asked without tools.
        ModelRequest review = model.requests.get(2);
        assertThat(review.instructions()).startsWith("The user asked: What now?. The draft: Buy..");
        assertThat(review.tools()).isEmpty();
        assertThat(review.messages().get(0).text()).contains("<agent_response>\nBuy.\n</agent_response>");
        // Whoever follows the session hears each reviewer begin and what it decided; a missing one says nothing.
        assertThat(heard).extracting(event -> event.kind() + " " + event.details())
                .filteredOn(line -> line.startsWith("review."))
                .containsExactly(
                        "review.started {reviewer=lenient}",
                        "review.finished " + Map.of("reviewer", "lenient", "verdict", "PASSED"),
                        "review.started {reviewer=editor}",
                        "review.finished " + Map.of("reviewer", "editor", "verdict", "MODIFIED"));
    }

    @Test
    void a_reviewer_that_blocks_replaces_the_answer_and_ends_the_review() throws InterruptedException {
        AgentDefinition advisor = new AgentDefinition("advisor", "Advises.", "You advise.",
                ModelSettings.ofModel("big"), List.of(), 5,
                Map.of(ResponseReviewPlugin.REVIEWERS, List.of("researcher", "researcher")));
        model.script.add(TurnMessage.assistant("Buy."));
        model.script.add(TurnMessage.assistant("BLOCK: I cannot advise on that."));

        Turn ended = awaitRest(turns.submit(TurnRequest.text(sessions.changeAgent(session.id(), advisor).id(), "What now?")));

        assertThat(ended.outcome().text()).isEqualTo("I cannot advise on that.");
        assertThat(model.requests.stream().filter(request -> request.settings().model().equals("small")))
                .hasSize(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Waits until the turn stands still: ended, or interrupted and waiting for an answer. */
    private Turn awaitRest(TurnId turnId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        Turn turn = turns.find(turnId).orElseThrow();
        while (!turn.status().isFinal() && turn.status() != TurnStatus.INTERRUPTED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            turn = turns.find(turnId).orElseThrow();
        }
        // A listener is told on a thread of its own, a moment after the turn stands still.
        Thread.sleep(50);
        return turn;
    }

    private List<String> toolResults(TurnId turnId) {
        return turns.messages(turnId).stream()
                .filter(message -> message.type() == TurnMessage.Type.TOOL_RESULT)
                .map(TurnMessage::text)
                .toList();
    }

    private static TurnMessage call(String callId, String toolName, Map<String, Object> arguments) {
        return TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, toolName, arguments)));
    }

    private static SkillResolver skills(SkillDefinition... skills) {
        return new SkillResolver() {
            @Override
            public List<SkillSummary> skills(Session session) {
                return List.of(skills).stream()
                        .map(skill -> new SkillSummary(skill.name(), skill.description())).toList();
            }

            @Override
            public Optional<SkillDefinition> resolve(String skillName, Session session) {
                return List.of(skills).stream().filter(skill -> skill.name().equals(skillName)).findFirst();
            }
        };
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Does " + name + ".", Map.of("type", "object"));
            }

            @Override
            public ToolOutput execute(ToolCall call, Session in) {
                return ToolOutput.ok("ran " + name);
            }
        };
    }
}
