package ai.mindconnect.extension.demo;

import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.util.List;

/**
 * The table. Before an adventure: the scenarios to choose from and the
 * player's saved games. During one: the character as last saved, the story
 * so far, a box for the next action, and the dice. Plain {@link UiPage}s —
 * the host wraps them in its shell because the manifest names the route.
 */
final class DungeonPage {

    static final String NAVIGATE = "/admin/demo-dungeon";
    static final String FORM_ID = "dungeon-form";

    private DungeonPage() {
    }

    /** The lobby: pick a scenario, or continue a saved adventure. */
    static UiPage lobby(List<Scenario> scenarios, List<Adventure> saved, String note, long llmCalls) {
        UiStack stack = UiStack.of("dungeon");
        stack.child(intro(llmCalls));
        if (note != null) stack.child(UiText.of("dungeon-note", note));

        UiList choose = UiList.of("dungeon-scenarios", "Choose a scenario").icon("map");
        for (Scenario scenario : scenarios) {
            choose.item(UiList.Item.of("scenario-" + scenario.id(), scenario.title())
                    .description(scenario.teaser())
                    .action(UiAction.primary("play-" + scenario.id(), "Play").icon("play")
                            .dispatch("POST", NAVIGATE + "/start/" + scenario.id())));
        }
        stack.child(choose);

        if (!saved.isEmpty()) {
            UiList games = UiList.of("dungeon-saved", "Your adventures").icon("book-open");
            for (Adventure adventure : saved) {
                var state = adventure.state();
                String where = state.isOver() ? state.status() : state.hitPoints() + " HP"
                        + (state.note().isBlank() ? "" : " · " + state.note());
                var item = UiList.Item.of("adventure-" + adventure.id(), adventure.scenarioTitle())
                        .description(where + " · " + adventure.story().size() + " lines");
                if (!state.isOver()) {
                    item.action(UiAction.secondary("continue-" + adventure.id(), "Continue").icon("play")
                            .dispatch("GET", NAVIGATE + "/play/" + adventure.id()));
                }
                item.action(UiAction.danger("forget-" + adventure.id(), "Forget").icon("delete")
                        .confirm("Forget this adventure?")
                        .dispatch("POST", NAVIGATE + "/forget/" + adventure.id()));
                games.item(item);
            }
            stack.child(games);
        }
        return UiPage.of(NAVIGATE, stack);
    }

    /** The table during an adventure. */
    static UiPage table(Adventure adventure, String note, long llmCalls) {
        UiStack stack = UiStack.of("dungeon");
        stack.child(intro(llmCalls));
        if (note != null) stack.child(UiText.of("dungeon-note", note));

        var state = adventure.state();
        stack.child(UiText.of("dungeon-character", adventure.scenarioTitle() + " — " + state.hitPoints() + " HP"
                + (state.inventory().isEmpty() ? "" : " · carrying " + String.join(", ", state.inventory()))
                + (state.note().isBlank() ? "" : " · " + state.note())
                + (state.isOver() ? " · " + state.status() : "")));

        UiList list = UiList.of("dungeon-story", "The story so far").icon("book-open");
        int i = 0;
        for (Adventure.Line line : adventure.story()) {
            list.item(UiList.Item.of("story-" + (i++), line.who()).description(line.text()));
        }
        stack.child(list);

        UiForm form = UiForm.of(FORM_ID, state.isOver() ? "The adventure is over" : "Your move");
        form.field(UiField.hidden("adventureId", adventure.id()));
        if (!state.isOver()) {
            form.field(UiField.textarea("action", "What do you do?", "").asEditable()
                    .placeholder("I sneak past the guard… / I talk to the innkeeper… / I attack!"));
            form.action(UiAction.primary("act", "Do it").icon("send").dispatch("POST", NAVIGATE + "/act", FORM_ID));
            form.action(UiAction.secondary("roll20", "Roll d20").icon("dice").dispatch("POST", NAVIGATE + "/roll/20", FORM_ID));
            form.action(UiAction.secondary("roll6", "Roll d6").icon("dice").dispatch("POST", NAVIGATE + "/roll/6", FORM_ID));
        }
        form.action(UiAction.secondary("lobby", "Back to the scenarios").icon("map").dispatch("GET", NAVIGATE));
        stack.child(form);
        return UiPage.of(NAVIGATE, stack);
    }

    private static UiText intro(long llmCalls) {
        return UiText.of("dungeon-intro",
                "Short adventures run by the agent `dungeon-master`: it tells the story, you decide and roll, and it saves "
                        + "your character through the tool `demo_state`. Agent, tools, scenarios, saved games, the store "
                        + "behind Data → Dice statistics and this screen come from the extension `demo-dungeon` — one jar "
                        + "with a manifest." + (llmCalls >= 0 ? " LLM calls seen by its decorator since start: " + llmCalls + "." : ""));
    }
}
