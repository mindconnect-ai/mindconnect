package ai.mindconnect.extension.demo;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Rolls in a list — for a runtime that keeps nothing. */
final class InMemoryDiceRollRepository implements DiceRollRepository {

    private final List<DiceRoll> rolls = new CopyOnWriteArrayList<>();

    @Override
    public void append(DiceRoll roll) {
        rolls.add(roll);
    }

    @Override
    public List<DiceRoll> since(Instant from) {
        return rolls.stream().filter(roll -> !roll.at().isBefore(from)).toList();
    }
}
