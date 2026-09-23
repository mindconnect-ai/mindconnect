package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.tool.Tool;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.function.Consumer;

/** Rolls dice — the one tool the demo extension brings, so the agent has something to call. */
public final class DiceTool implements Tool {

    private final Random random;
    /** Where every rolled die goes — the extension's store, or nowhere. */
    private final Consumer<DiceRoll> rolls;

    public DiceTool() {
        this(new Random(), roll -> { });
    }

    DiceTool(Random random, Consumer<DiceRoll> rolls) {
        this.random = random;
        this.rolls = rolls;
    }

    @Override
    public String name() {
        return "demo_dice";
    }

    @Override
    public String description() {
        return "Rolls dice. `sides` is how many faces a die has (default 6), `count` how many dice to roll (default 1). "
                + "Returns each roll and the sum.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sides", Map.of("type", "integer", "description", "Faces per die, 2 to 1000", "default", 6),
                        "count", Map.of("type", "integer", "description", "Dice to roll, 1 to 100", "default", 1)),
                "required", new String[0]);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        int sides = clamp(number(arguments.get("sides"), 6), 2, 1000);
        int count = clamp(number(arguments.get("count"), 1), 1, 100);
        StringJoiner joined = new StringJoiner(", ");
        int sum = 0;
        for (int i = 0; i < count; i++) {
            int roll = 1 + random.nextInt(sides);
            sum += roll;
            joined.add(Integer.toString(roll));
            rolls.accept(DiceRoll.of(Instant.now(), sides, roll));
        }
        return count + "d" + sides + ": " + joined + " (sum " + sum + ")";
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
