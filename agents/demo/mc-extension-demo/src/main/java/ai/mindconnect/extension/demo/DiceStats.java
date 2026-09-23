package ai.mindconnect.extension.demo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/** Rolls per day and per face — what the statistics screen draws. */
record DiceStats(Map<LocalDate, Map<Integer, Long>> byDay) {

    /** The rolls grouped by the day they fell on, in {@code zone}, and by the face that came up. */
    static DiceStats of(List<DiceRoll> rolls, ZoneId zone) {
        Map<LocalDate, Map<Integer, Long>> byDay = new TreeMap<>();
        for (DiceRoll roll : rolls) {
            LocalDate day = roll.at().atZone(zone).toLocalDate();
            byDay.computeIfAbsent(day, d -> new TreeMap<>()).merge(roll.value(), 1L, Long::sum);
        }
        return new DiceStats(byDay);
    }

    boolean isEmpty() {
        return byDay.isEmpty();
    }

    /** Every face that came up on any day, ascending. */
    TreeSet<Integer> faces() {
        TreeSet<Integer> faces = new TreeSet<>();
        byDay.values().forEach(counts -> faces.addAll(counts.keySet()));
        return faces;
    }

    long count(LocalDate day, int face) {
        return byDay.getOrDefault(day, Map.of()).getOrDefault(face, 0L);
    }

    long total(LocalDate day) {
        return byDay.getOrDefault(day, Map.of()).values().stream().mapToLong(Long::longValue).sum();
    }

    /** The face that fell most that day, and how often; the lower face on a tie. */
    Optional<Map.Entry<Integer, Long>> topFace(LocalDate day) {
        return byDay.getOrDefault(day, Map.of()).entrySet().stream()
                .max((a, b) -> a.getValue().equals(b.getValue())
                        ? Integer.compare(b.getKey(), a.getKey())
                        : Long.compare(a.getValue(), b.getValue()));
    }
}
