package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiceStatsTest {

    private static DiceRoll roll(String at, int value) {
        return DiceRoll.of(Instant.parse(at), 6, value);
    }

    @Test
    void groups_rolls_by_day_and_face_and_names_the_day_s_top_face() {
        DiceStats stats = DiceStats.of(List.of(
                roll("2026-09-22T10:00:00Z", 6), roll("2026-09-22T11:00:00Z", 6), roll("2026-09-22T12:00:00Z", 2),
                roll("2026-09-23T09:00:00Z", 3), roll("2026-09-23T09:30:00Z", 5)), ZoneOffset.UTC);

        LocalDate d22 = LocalDate.of(2026, 9, 22);
        LocalDate d23 = LocalDate.of(2026, 9, 23);
        assertThat(stats.byDay().keySet()).containsExactly(d22, d23);
        assertThat(stats.faces()).containsExactly(2, 3, 5, 6);
        assertThat(stats.count(d22, 6)).isEqualTo(2);
        assertThat(stats.total(d22)).isEqualTo(3);
        assertThat(stats.topFace(d22).orElseThrow().getKey()).isEqualTo(6);
        // A tie goes to the lower face.
        assertThat(stats.topFace(d23).orElseThrow().getKey()).isEqualTo(3);
    }

    @Test
    void the_page_draws_a_chart_with_one_series_per_face_and_says_when_there_is_nothing() throws Exception {
        DiceStats stats = DiceStats.of(List.of(roll("2026-09-22T10:00:00Z", 6), roll("2026-09-22T11:00:00Z", 1)),
                ZoneOffset.UTC);
        String json = new ObjectMapper().writeValueAsString(DiceStatsPage.render(stats, 30));

        assertThat(json).contains("\"type\":\"chart\"").contains("Face 1").contains("Face 6").contains("2026-09-22")
                .contains("Most frequent face per day");

        String empty = new ObjectMapper().writeValueAsString(DiceStatsPage.render(DiceStats.of(List.of(), ZoneOffset.UTC), 30));
        assertThat(empty).contains("No rolls yet").doesNotContain("\"type\":\"chart\"");
    }

    @Test
    void the_file_store_appends_per_namespace_and_reads_back_since(@TempDir Path dir) {
        var acme = new FileDiceRollRepository(dir, new Namespace("acme"));
        var other = new FileDiceRollRepository(dir, new Namespace("other"));

        acme.append(roll("2026-09-22T10:00:00Z", 4));
        acme.append(roll("2026-09-23T10:00:00Z", 2));

        assertThat(dir.resolve("acme/ext/demo-dungeon/dice-rolls.jsonl")).exists();
        assertThat(acme.since(Instant.parse("2026-09-23T00:00:00Z"))).extracting(DiceRoll::value).containsExactly(2);
        assertThat(acme.since(Instant.EPOCH)).hasSize(2);
        assertThat(other.since(Instant.EPOCH)).isEmpty();
    }

    @Test
    void the_tool_hands_every_die_to_the_store() {
        var store = new InMemoryDiceRollRepository();
        DiceTool tool = new DiceTool(new java.util.Random(7), store::append);

        tool.execute(java.util.Map.of("count", 3));

        assertThat(store.since(Instant.EPOCH)).hasSize(3).allSatisfy(r -> assertThat(r.sides()).isEqualTo(6));
    }
}
