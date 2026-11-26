package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;
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

    private final Path dataDir;
    private final ScopeSupplier scope;
    private final ObjectMapper mapper;

    FileDiceRollRepository(Path dataDir, ScopeSupplier scope, JsonMapperFactory json) {
        this.dataDir = dataDir.toAbsolutePath();
        this.scope = scope;
        this.mapper = json.create().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** The rolls of the namespace the current call works in. */
    private Path file() {
        return dataDir.resolve(scope.namespace().value()).resolve("ext").resolve("demo-dungeon").resolve("dice-rolls.jsonl");
    }

    @Override
    public synchronized void append(DiceRoll roll) {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(roll) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to " + file, e);
        }
    }

    @Override
    public synchronized List<DiceRoll> since(Instant from) {
        Path file = file();
        if (!Files.isRegularFile(file)) return List.of();
        List<DiceRoll> rolls = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                DiceRoll roll = mapper.readValue(line, DiceRoll.class);
                if (!roll.at().isBefore(from)) rolls.add(roll);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return rolls;
    }
}
