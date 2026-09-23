package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoStoreTest {

    @Test
    void the_file_store_keeps_one_document_per_record_in_the_extension_s_corner(@TempDir Path dir) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DemoStore<Scenario> acme = DemoStore.onFiles(dir, new Namespace("acme"), mapper, Scenario.class, "scenario", Scenario::id);
        DemoStore<Scenario> other = DemoStore.onFiles(dir, new Namespace("other"), mapper, Scenario.class, "scenario", Scenario::id);

        acme.save(Scenario.SEED.get(0));
        acme.save(Scenario.SEED.get(1));

        assertThat(dir.resolve("acme/ext/demo-dungeon/scenario/goblin-cave.json")).exists();
        assertThat(acme.find("goblin-cave")).isPresent().get().extracting(Scenario::title).isEqualTo("The Goblin Cave");
        assertThat(acme.all()).hasSize(2);
        assertThat(other.all()).isEmpty();

        acme.delete("goblin-cave");
        assertThat(acme.find("goblin-cave")).isEmpty();
    }

    @Test
    void an_adventure_round_trips_with_its_story_and_state(@TempDir Path dir) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DemoStore<Adventure> store = DemoStore.onFiles(dir, new Namespace("acme"), mapper, Adventure.class, "adventure", Adventure::id);
        Adventure adventure = Adventure.start(Scenario.SEED.get(2), "s-1", "david")
                .withLines(Adventure.Line.master("Midnight."), Adventure.Line.player("I knock."))
                .withState(new Adventure.CharacterState(8, List.of("cloak", "knife"), "at the door", "RUNNING"));

        store.save(adventure);

        Adventure back = store.find(adventure.id()).orElseThrow();
        assertThat(back.story()).extracting(Adventure.Line::text).containsExactly("Midnight.", "I knock.");
        assertThat(back.state().inventory()).containsExactly("cloak", "knife");
        assertThat(back.scenarioTitle()).isEqualTo("The Thieves' Market");
    }

    @Test
    void an_empty_scenario_store_is_seeded_on_first_use() {
        ScenarioStore scenarios = new ScenarioStore(DemoStore.inMemory(Scenario::id));

        assertThat(scenarios.all()).extracting(Scenario::id).containsExactlyInAnyOrder("goblin-cave", "haunted-lighthouse", "thieves-market");
        assertThat(scenarios.all()).hasSize(3);
    }
}
