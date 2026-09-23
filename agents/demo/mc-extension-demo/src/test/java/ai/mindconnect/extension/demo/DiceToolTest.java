package ai.mindconnect.extension.demo;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DiceToolTest {

    @Test
    void rolls_the_dice_asked_for_and_sums_them() {
        DiceTool tool = new DiceTool(new Random(42), roll -> { });

        String result = tool.execute(Map.of("sides", 6, "count", 3));

        assertThat(result).startsWith("3d6: ").contains("(sum ");
        String[] rolls = result.substring(5, result.indexOf(" (")).split(", ");
        assertThat(rolls).hasSize(3);
        for (String roll : rolls) {
            assertThat(Integer.parseInt(roll)).isBetween(1, 6);
        }
    }

    @Test
    void defaults_to_one_six_sided_die_and_clamps_nonsense() {
        DiceTool tool = new DiceTool(new Random(1), roll -> { });

        assertThat(tool.execute(Map.of())).startsWith("1d6: ");
        assertThat(tool.execute(Map.of("sides", "x", "count", "0"))).startsWith("1d6: ");
        assertThat(tool.execute(Map.of("sides", 1, "count", 1000))).startsWith("100d2: ");
    }

    @Test
    void the_factory_names_the_tool_and_its_group() {
        DiceToolFactory factory = new DiceToolFactory();

        assertThat(factory.name()).isEqualTo("demo_dice");
        assertThat(factory.group()).isEqualTo("demo");
        assertThat(factory.create(null, null).name()).isEqualTo("demo_dice");
    }
}
