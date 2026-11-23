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
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.engine.adapter.memory.InMemorySessionStorePlugin;
import ai.mindconnect.agent.runtime.engine.adapter.prompt.PebblePromptPlugin;
import ai.mindconnect.agent.runtime.engine.service.RuntimePlugin;
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
                into.provide(NamespaceProvider.class, () -> NamespaceProvider.fixed("test"));
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
        registry.install(new RuntimePlugin(queue, new InMemorySharedStateStore()));
        registry.contribute(AgentResolver.class, AgentResolver.of(List.of(LEAD, RESEARCHER)));
        registry.contribute(SkillResolver.class, skills(
                new SkillDefinition("citations", "How to cite sources.", "Cite like this.", List.of())));
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(tool("bash"), tool("read_pdf"))));
        registry.install(new SkillPlugin());
        registry.install(new ToolSearchPlugin(new InMemoryToolActivation(NamespaceProvider.fixed("test"))));
        registry.install(new SubAgentPlugin());
        registry.install(new PermissionPlugin(PermissionEffect.ALLOW, new InMemoryApprovalMemory(NamespaceProvider.fixed("test"))));
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
                "bash", "ask_user", "load_skill", "tool_search", "researcher");
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
        model.script.add(call("c1", "researcher", Map.of("task", "Find the capital of France.")));
        model.script.add(call("c2", "bash", Map.of()));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Capital of France?"));
        Turn interrupted = awaitRest(turnId);
        TurnId subAgent = interrupted.interruptions().get(0).turnId();

        assertThat(interrupted.parent()).isNull();
        assertThat(turns.find(subAgent)).map(Turn::parent).contains(turnId);
        turns.cancel(turnId);
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

        awaitRest(turns.submit(new TurnRequest(session.id(),
                List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("Hi")), greeter, Map.of())));

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

        assertThat(heard).hasAtLeastOneElementOfType(ai.mindconnect.agent.runtime.api.domain.SessionEvent.TurnStarted.class)
                .allMatch(event -> event.sessionId().equals(session.id()));
    }

    @Test
    void whoever_follows_a_session_hears_what_its_turns_report() throws InterruptedException {
        List<ai.mindconnect.agent.runtime.api.domain.SessionEvent> heard =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.get(ai.mindconnect.agent.runtime.api.port.in.SessionService.class)
                .subscribe(session.id(), false, heard::add);
        model.script.add(call("c1", "bash", Map.of()));

        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Run it"));
        awaitRest(turnId);

        assertThat(heard).filteredOn(ai.mindconnect.agent.runtime.api.domain.SessionEvent.TurnReported.class::isInstance)
                .extracting(event -> ((ai.mindconnect.agent.runtime.api.domain.SessionEvent.TurnReported) event)
                        .event().getClass().getSimpleName())
                .contains("TurnStarted", "MessageAppended", "TurnWaiting");
        // A turn that waits has not finished.
        assertThat(heard).noneMatch(
                ai.mindconnect.agent.runtime.api.domain.SessionEvent.TurnFinished.class::isInstance);
        turns.cancel(turnId);
    }

    @Test
    void whoever_follows_a_turn_is_told_its_end_also_when_coming_late() throws InterruptedException {
        model.script.add(TurnMessage.assistant("Hello."));
        TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Hi"));
        awaitRest(turnId);
        List<Turn> finished = new java.util.concurrent.CopyOnWriteArrayList<>();

        turns.subscribe(turnId, new ai.mindconnect.agent.runtime.api.port.in.TurnListener() {
            @Override
            public void onEvent(ai.mindconnect.agent.loop.domain.TurnEvent event) { }

            @Override
            public void onFinished(Turn turn) {
                finished.add(turn);
            }
        });

        for (int i = 0; i < 200 && finished.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertThat(finished).extracting(Turn::status).containsExactly(TurnStatus.COMPLETED);
    }

    @Test
    void a_sub_agent_runs_a_turn_of_its_own_and_its_answer_is_the_tools_result() throws InterruptedException {
        model.script.add(call("c1", "researcher", Map.of("task", "Find the capital of France.")));
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
    }

    @Test
    void an_approval_a_sub_agent_needs_comes_back_on_the_callers_turn() throws InterruptedException {
        registry.contribute(AgentResolver.class, (name, in) ->
                "operator".equals(name) ? Optional.of(new AgentDefinition("operator", "Runs commands.",
                        "You operate.", ModelSettings.NONE, ToolBinding.allOf("bash"), 5, Map.of()))
                        : Optional.empty());
        AgentDefinition boss = new AgentDefinition("boss", "Delegates.", "You delegate.", ModelSettings.NONE,
                List.of(), 5, Map.of(SubAgentPlugin.SUB_AGENTS, List.of("operator")));
        model.script.add(call("c1", "operator", Map.of("task", "Run it.")));
        model.script.add(call("c2", "bash", Map.of()));
        model.script.add(TurnMessage.assistant("Ran."));
        model.script.add(TurnMessage.assistant("The operator ran it."));

        TurnId turnId = turns.submit(new TurnRequest(session.id(),
                List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("Go")), boss, Map.of()));
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
        sessions.subscribe(session.id(), false, heard::add);
        model.script.add(TurnMessage.assistant("Hello."));
        model.script.add(TurnMessage.assistant("A greeting"));

        awaitRest(turns.submit(TurnRequest.text(session.id(), "Hi")));
        long deadline = System.currentTimeMillis() + 5_000;
        while (heard.stream().noneMatch(SessionEvent.Notice.class::isInstance)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(heard).filteredOn(SessionEvent.Notice.class::isInstance).singleElement()
                .isEqualTo(new SessionEvent.Notice(session.id(), null, "session.title",
                        Map.of("title", "A greeting")));
        assertThat(sessions.find(session.id()).orElseThrow().metadata()).containsEntry("title", "A greeting");
        assertThat(heard).filteredOn(event -> !(event instanceof SessionEvent.TurnReported))
                .first().isInstanceOf(SessionEvent.TurnStarted.class);
        assertThat(heard).anyMatch(SessionEvent.TurnFinished.class::isInstance);
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
    void a_tool_the_agent_has_switched_off_is_neither_offered_nor_run() throws InterruptedException {
        AgentDefinition careful = new AgentDefinition("careful", "Is careful.", "You are careful.",
                ModelSettings.ofModel("big"),
                List.of(new ToolBinding("read_pdf", false, Map.of(ToolBinding.ENABLED, false))), 5, Map.of());
        model.script.add(call("c1", "read_pdf", Map.of()));
        model.script.add(TurnMessage.assistant("Could not read it."));

        Turn ended = awaitRest(turns.submit(new TurnRequest(session.id(),
                List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("Read it.")), careful, Map.of())));

        assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(model.requests.get(0).tools()).extracting(ToolDefinition::name).doesNotContain("read_pdf");
        assertThat(toolResults(ended.id())).noneMatch(result -> result.contains("ran read_pdf"));
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

        Turn ended = awaitRest(turns.submit(new TurnRequest(session.id(),
                List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("What now?")), advisor, Map.of())));

        assertThat(ended.outcome().text()).isEqualTo("Buy. This is not investment advice.");
        assertThat(turns.messages(ended.id())).last().extracting(TurnMessage::text)
                .isEqualTo("Buy. This is not investment advice.");
        // The reviewer reads the question and the draft in its instructions, and is asked without tools.
        ModelRequest review = model.requests.get(2);
        assertThat(review.instructions()).startsWith("The user asked: What now?. The draft: Buy..");
        assertThat(review.tools()).isEmpty();
        assertThat(review.messages().get(0).text()).contains("<agent_response>\nBuy.\n</agent_response>");
    }

    @Test
    void a_reviewer_that_blocks_replaces_the_answer_and_ends_the_review() throws InterruptedException {
        AgentDefinition advisor = new AgentDefinition("advisor", "Advises.", "You advise.",
                ModelSettings.ofModel("big"), List.of(), 5,
                Map.of(ResponseReviewPlugin.REVIEWERS, List.of("researcher", "researcher")));
        model.script.add(TurnMessage.assistant("Buy."));
        model.script.add(TurnMessage.assistant("BLOCK: I cannot advise on that."));

        Turn ended = awaitRest(turns.submit(new TurnRequest(session.id(),
                List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("What now?")), advisor, Map.of())));

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
