package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
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
        List<DiceRoll> rolls = runtime.beans().find(DiceRollRepository.class)
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
            SessionId session = runtime.openSession(AGENT, me()).id();
            Adventure adventure = Adventure.start(scenario.get(), session.value(), me().value());
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
        DiceTool dice = new DiceTool(random, runtime.beans().find(DiceRollRepository.class)
                .<Consumer<DiceRoll>>map(store -> store::append).orElse(roll -> { }));
        String result = dice.execute(Map.of("sides", sides, "count", 1));
        String value = result.substring(result.indexOf(':') + 2, result.indexOf(" ("));
        return turn(adventure.get(), "I roll a d" + sides + ": " + value + ".", "🎲 d" + sides + " → " + value);
    }

    /** One turn: what the player said goes to the game master, both lines join the story, the record is saved. */
    private UiPage turn(Adventure adventure, String message, String shownAsPlayer) {
        try {
            String reply = runtime.chat(SessionId.of(adventure.sessionId()), message, event -> { });
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

    private Optional<Adventure> adventure(String id) {
        if (id == null) return Optional.empty();
        return adventures().flatMap(store -> store.store().find(id)).filter(a -> me().value().equals(a.userId()));
    }

    private Optional<ScenarioStore> scenarios() {
        return runtime.beans().find(ScenarioStore.class);
    }

    private Optional<AdventureStore> adventures() {
        return runtime.beans().find(AdventureStore.class);
    }

    private UserId me() {
        return scope.get().userIfAny().orElse(UserId.of("demo"));
    }

    /** What the decorator counted — the bean the demo feature registered in the runtime, if the feature is installed. */
    private long llmCalls() {
        return runtime.beans().find(LlmCallCounter.class).map(LlmCallCounter::calls).orElse(-1L);
    }

    /** The agent is seeded from the jar's {@code initial-data} — into the start-up namespace; elsewhere it may be missing. */
    private String agentMissingNote() {
        if (runtime.agentDefinitions().findByName(AGENT).isPresent()) return null;
        return "The agent '" + AGENT + "' is not installed in this namespace yet — Install → Migrations imports it.";
    }

    private static String text(Map<String, Object> form, String key) {
        Object value = form.get(key);
        if (value == null) return null;
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
