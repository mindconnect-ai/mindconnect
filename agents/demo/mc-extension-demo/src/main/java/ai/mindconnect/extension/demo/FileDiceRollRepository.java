package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls as JSON lines under {@code <dataDir>/<namespace>/ext/demo-dungeon/dice-rolls.jsonl}
 * — the extension's own corner of the namespace's directory, appended to,
 * read whole. Small by design: a demo rolls a few dice, not millions.
 */
final class FileDiceRollRepository implements DiceRollRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;

    FileDiceRollRepository(Path dataDir, Namespace namespace) {
        this.file = dataDir.resolve(namespace.value()).resolve("ext").resolve("demo-dungeon")
                .resolve("dice-rolls.jsonl").toAbsolutePath();
    }

    @Override
    public synchronized void append(DiceRoll roll) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(roll) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to " + file, e);
        }
    }

    @Override
    public synchronized List<DiceRoll> since(Instant from) {
        if (!Files.isRegularFile(file)) return List.of();
        List<DiceRoll> rolls = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                DiceRoll roll = MAPPER.readValue(line, DiceRoll.class);
                if (!roll.at().isBefore(from)) rolls.add(roll);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return rolls;
    }
}
