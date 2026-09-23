package ai.mindconnect.extension.demo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One die rolled: when, how many faces it had, and what came up. */
public record DiceRoll(String id, Instant at, int sides, int value) {

    public DiceRoll {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
    }

    public static DiceRoll of(Instant at, int sides, int value) {
        return new DiceRoll(UUID.randomUUID().toString(), at, sides, value);
    }
}
