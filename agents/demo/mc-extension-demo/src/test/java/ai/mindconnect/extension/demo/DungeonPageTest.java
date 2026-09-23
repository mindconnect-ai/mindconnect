package ai.mindconnect.extension.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DungeonPageTest {

    private static final Scenario CAVE = Scenario.SEED.get(0);

    @Test
    void the_lobby_offers_the_scenarios_and_the_saved_games() throws Exception {
        Adventure running = Adventure.start(CAVE, "s-1", "david")
                .withState(new Adventure.CharacterState(7, List.of("bow", "rope"), "at the cave mouth", "RUNNING"));
        Adventure won = Adventure.start(CAVE, "s-2", "david")
                .withState(new Adventure.CharacterState(3, List.of(), "", "WON"));
        String json = new ObjectMapper().writeValueAsString(DungeonPage.lobby(Scenario.SEED, List.of(running, won), null, 0));

        assertThat(json).contains("\"navigate\":\"/admin/demo-dungeon\"")
                .contains("\"url\":\"/admin/demo-dungeon/start/goblin-cave\"")
                .contains("\"url\":\"/admin/demo-dungeon/start/haunted-lighthouse\"")
                .contains("7 HP · at the cave mouth")
                .contains("\"url\":\"/admin/demo-dungeon/play/" + running.id() + "\"")
                .doesNotContain("/play/" + won.id())
                .contains("\"url\":\"/admin/demo-dungeon/forget/" + won.id() + "\"")
                .contains("LLM calls seen by its decorator since start: 0");
    }

    @Test
    void the_table_shows_the_character_the_story_and_the_dice() throws Exception {
        Adventure adventure = Adventure.start(CAVE, "s-1", "david")
                .withLines(Adventure.Line.master("You stand at the cave mouth."), Adventure.Line.player("🎲 d20 → 14"))
                .withState(new Adventure.CharacterState(9, List.of("short bow", "torch"), "inside the cave", "RUNNING"));
        String json = new ObjectMapper().writeValueAsString(DungeonPage.table(adventure, null, -1));

        assertThat(json).contains("You stand at the cave mouth.").contains("🎲 d20 → 14")
                .contains("The Goblin Cave — 9 HP · carrying short bow, torch · inside the cave")
                .contains("\"value\":\"" + adventure.id() + "\"")
                .contains("\"url\":\"/admin/demo-dungeon/act\"")
                .contains("\"url\":\"/admin/demo-dungeon/roll/20\"")
                .contains("\"url\":\"/admin/demo-dungeon/roll/6\"")
                .doesNotContain("LLM calls seen");
    }

    @Test
    void an_adventure_that_is_over_offers_no_move() throws Exception {
        Adventure lost = Adventure.start(CAVE, "s-1", "david")
                .withState(new Adventure.CharacterState(0, List.of(), "eaten by the wolf", "LOST"));
        String json = new ObjectMapper().writeValueAsString(DungeonPage.table(lost, null, -1));

        assertThat(json).contains("The adventure is over").contains("LOST")
                .doesNotContain("/roll/20").doesNotContain("/admin/demo-dungeon/act");
    }

    @Test
    void the_state_tool_saves_into_the_adventure_of_its_session() {
        DemoStore<Adventure> store = DemoStore.inMemory(Adventure::id);
        store.save(Adventure.start(CAVE, "s-1", "david"));
        store.save(Adventure.start(CAVE, "s-2", "david"));

        String saved = new StateTool(store, "s-2").execute(java.util.Map.of(
                "hitPoints", 6, "inventory", "rope, torch", "note", "on the bridge", "status", "running"));
        String nowhere = new StateTool(store, "s-9").execute(java.util.Map.of("hitPoints", 1, "status", "LOST"));

        assertThat(saved).startsWith("Saved: 6 HP, 2 item(s), status RUNNING");
        assertThat(nowhere).contains("nothing saved");
        Adventure two = store.all().stream().filter(a -> a.sessionId().equals("s-2")).findFirst().orElseThrow();
        assertThat(two.state().inventory()).containsExactly("rope", "torch");
        assertThat(two.state().note()).isEqualTo("on the bridge");
        Adventure one = store.all().stream().filter(a -> a.sessionId().equals("s-1")).findFirst().orElseThrow();
        assertThat(one.state()).isEqualTo(Adventure.CharacterState.FRESH);
    }
}
