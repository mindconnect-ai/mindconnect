package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.ui.model.UiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Runs the adventures through the runtime: starting one opens a session
 * with the game master for the signed-in user and tells it the scenario;
 * every action and every roll is a turn in that session. The player's dice
 * are rolled here with the same tool the game master has, so they land in
 * the extension's store and count on the statistics screen. The story and
 * the character's state live in the adventure record, which the game master
 * updates through {@code demo_state} — the game survives leaving and restarts.
 *
 * <p>Answers JSON on the page URL; the host's same-URL filter hands a browser
 * the shell, and the host wraps the page in its layout because the manifest
 * names {@code /admin/demo-dungeon/**}.
 */
@RestController
@RequestMapping(DungeonPage.NAVIGATE)
public class DungeonController {

    private static final Logger log = LoggerFactory.getLogger(DungeonController.class);
    static final String AGENT = "dungeon-master";

    /** How long a page waits for the game master's reply. */
    private static final Duration ANSWER_WAIT = Duration.ofMinutes(5);

    private final AgentRuntime runtime;
    private final ScopeSupplier scope;
    private final Random random = new Random();

    public DungeonController(AgentRuntime runtime, ScopeSupplier scope) {
        this.runtime = runtime;
        this.scope = scope;
    }

    @GetMapping
    public UiPage lobby() {
        return lobby(agentMissingNote());
    }

    private UiPage lobby(String note) {
        List<Scenario> scenarios = scenarios().map(ScenarioStore::all).orElse(List.of());
        UserId me = me();
        List<Adventure> mine = adventures().map(store -> store.store().all().stream()
                .filter(a -> me.value().equals(a.userId()))
                .sorted(Comparator.comparing(Adventure::updatedAt).reversed())
                .toList()).orElse(List.of());
        String hint = note;
        if (hint == null && scenarios().isEmpty()) {
            hint = "The demo feature is not installed in this runtime — no scenarios, nothing to save.";
        }
        return DungeonPage.lobby(scenarios, mine, hint, llmCalls());
    }

    /** Which face fell most, per day, from the extension's own store — the player's dice and the game master's. */
    @GetMapping("/stats")
    public UiPage stats() {
        int days = 30;
        List<DiceRoll> rolls = runtime.find(DiceRollRepository.class)
                .map(store -> store.since(Instant.now().minus(Duration.ofDays(days))))
                .orElse(List.of());
        return DiceStatsPage.render(DiceStats.of(rolls, ZoneId.systemDefault()), days);
    }

    /** Opens a session for the scenario and lets the game master set the scene. */
    @PostMapping("/start/{scenarioId}")
    public UiPage start(@PathVariable("scenarioId") String scenarioId) {
        String note = agentMissingNote();
        if (note != null) return lobby(note);
        Optional<Scenario> scenario = scenarios().flatMap(s -> s.store().find(scenarioId));
        if (scenario.isEmpty()) return lobby("No such scenario: " + scenarioId);
        try {
            String session = runtime.service(SessionManagement.class).open(
                    me(), null,
                    new SessionAgent.Ref(runtime.service(AgentManagement.class).findByName(AGENT).orElseThrow().id()),
                    null, SessionDirectories.NONE).id().value();
            Adventure adventure = Adventure.start(scenario.get(), session, me().value());
            adventures().orElseThrow().store().save(adventure);
            return turn(adventure, "We play the scenario \"" + scenario.get().title() + "\". Premise: "
                    + scenario.get().premise() + "\n\nStart the adventure: set the scene in a few sentences, tell me who I am "
                    + "and what I carry, and ask what I do.", null);
        } catch (RuntimeException e) {
            log.warn("The game master could not start: {}", e.toString());
            return lobby("The game master could not start: " + e.getMessage());
        }
    }

    @GetMapping("/play/{adventureId}")
    public UiPage play(@PathVariable("adventureId") String adventureId) {
        return adventure(adventureId).map(a -> DungeonPage.table(a, null, llmCalls()))
                .orElseGet(() -> lobby("No such adventure."));
    }

    @PostMapping("/forget/{adventureId}")
    public UiPage forget(@PathVariable("adventureId") String adventureId) {
        // Only one's own: adventure() finds nothing that belongs to somebody else.
        if (adventure(adventureId).isEmpty()) return lobby("No such adventure.");
        adventures().ifPresent(store -> store.store().delete(adventureId));
        return lobby(null);
    }

    /** The form arrives as a JSON body — the fields of the form by name, like every semantic-ui form. */
    @PostMapping("/act")
    public UiPage act(@RequestBody Map<String, Object> form) {
        Optional<Adventure> adventure = adventure(text(form, "adventureId"));
        if (adventure.isEmpty()) return lobby("No such adventure.");
        String action = text(form, "action");
        if (action == null) return DungeonPage.table(adventure.get(), "Say what you do first.", llmCalls());
        return turn(adventure.get(), action, action);
    }

    /** The player rolls: the die is rolled here, told to the game master, and lands in the store like every roll. */
    @PostMapping("/roll/{sides}")
    public UiPage roll(@PathVariable("sides") int sides, @RequestBody Map<String, Object> form) {
        Optional<Adventure> adventure = adventure(text(form, "adventureId"));
        if (adventure.isEmpty()) return lobby("No such adventure.");
        DiceTool dice = new DiceTool(random, runtime.find(DiceRollRepository.class)
                .<Consumer<DiceRoll>>map(store -> store::append).orElse(roll -> { }));
        String result = dice.execute(Map.of("sides", sides, "count", 1));
        String value = result.substring(result.indexOf(':') + 2, result.indexOf(" ("));
        return turn(adventure.get(), "I roll a d" + sides + ": " + value + ".", "🎲 d" + sides + " → " + value);
    }

    /** One turn: what the player said goes to the game master, both lines join the story, the record is saved. */
    private UiPage turn(Adventure adventure, String message, String shownAsPlayer) {
        try {
            String reply = ask(adventure.sessionId(), message);
            // The game master may have saved state during the turn: reload before appending the lines.
            Adventure current = adventure(adventure.id()).orElse(adventure);
            Adventure next = shownAsPlayer == null
                    ? current.withLines(Adventure.Line.master(reply))
                    : current.withLines(Adventure.Line.player(shownAsPlayer), Adventure.Line.master(reply));
            adventures().orElseThrow().store().save(next);
            return DungeonPage.table(next, null, llmCalls());
        } catch (RuntimeException e) {
            log.warn("The game master could not answer: {}", e.toString());
            return DungeonPage.table(adventure, "The game master could not answer: " + e.getMessage(), llmCalls());
        }
    }

    /**
     * One turn of the game master, waited for: the page is answered with the
     * reply. A turn that stops to ask — a tool that needs approval — has
     * nobody to answer it on this page, so it is given up.
     */
    private String ask(String sessionId, String message) {
        TurnId turnId = runtime.turns().submit(TurnRequest.text(
                ai.mindconnect.agent.SessionId.of(sessionId), message));
        java.util.concurrent.CompletableFuture<Turn> ended = new java.util.concurrent.CompletableFuture<>();
        Subscription following = runtime.turns().subscribe(turnId, new TurnListener() {
            @Override
            public void onEvent(TurnEvent event) {
            }

            @Override
            public void onFinished(Turn turn) {
                ended.complete(turn);
            }
        });
        try {
            long deadline = System.nanoTime() + ANSWER_WAIT.toNanos();
            while (!ended.isDone()) {
                Turn now = runtime.turns().find(turnId).orElseThrow();
                if (now.status().isFinal()) {
                    ended.complete(now);
                } else if (!now.interruptions().isEmpty() || System.nanoTime() > deadline) {
                    runtime.turns().cancel(turnId);
                    throw new IllegalStateException(now.interruptions().isEmpty()
                            ? "no answer within " + ANSWER_WAIT.toMinutes() + " min"
                            : "the game master asked for an approval nobody can give here");
                } else {
                    Thread.sleep(50);
                }
            }
            Turn turn = ended.join();
            if (turn.status() != TurnStatus.COMPLETED || turn.outcome() == null) {
                throw new IllegalStateException(turn.error() != null ? turn.error()
                        : "the turn ended " + turn.status().name().toLowerCase(java.util.Locale.ROOT));
            }
            return turn.outcome().text() == null ? "" : turn.outcome().text();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runtime.turns().cancel(turnId);
            throw new IllegalStateException("interrupted", e);
        } finally {
            following.close();
        }
    }

    private Optional<Adventure> adventure(String id) {
        if (id == null) return Optional.empty();
        return adventures().flatMap(store -> store.store().find(id)).filter(a -> me().value().equals(a.userId()));
    }

    private Optional<ScenarioStore> scenarios() {
        return runtime.find(ScenarioStore.class);
    }

    private Optional<AdventureStore> adventures() {
        return runtime.find(AdventureStore.class);
    }

    private UserId me() {
        return scope.get().userIfAny().orElse(UserId.of("demo"));
    }

    /** What the decorator counted — the service the demo plugin provides in the runtime, if the plugin is installed. */
    private long llmCalls() {
        return runtime.find(LlmCallCounter.class).map(LlmCallCounter::calls).orElse(-1L);
    }

    /** The agent is seeded from the jar's {@code initial-data} — into the start-up namespace; elsewhere it may be missing. */
    private String agentMissingNote() {
        if (runtime.service(AgentManagement.class).findByName(AGENT).isPresent()) return null;
        return "The agent '" + AGENT + "' is not installed in this namespace yet — Install → Migrations imports it.";
    }

    private static String text(Map<String, Object> form, String key) {
        Object value = form.get(key);
        if (value == null) return null;
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
