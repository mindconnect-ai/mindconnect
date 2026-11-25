package ai.mindconnect.agent.migration.earlier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.filerepo.FileRepo;

/**
 * The earlier runtime's files of a namespace: agents under
 * {@code system/agents/{id}.json}, sessions under
 * {@code sessions/{id}/session.json}, a conversation's summaries under
 * {@code conversations/{id}/summaries/}.
 */
public final class FileEarlierRuntimeData implements EarlierRuntimeData {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final Path base;
    private final ObjectMapper json;
    private final ScopeSupplier scope;

    public FileEarlierRuntimeData(Path base, ObjectMapper json, ScopeSupplier scope) {
        this.base = base;
        this.json = json;
        this.scope = scope;
    }

    private Path namespace() {
        return FileRepo.open(base, scope.namespace().value()).root();
    }

    @Override
    public List<String> agents() {
        return files(namespace().resolve("system/agents")).map(FileEarlierRuntimeData::read).toList();
    }

    @Override
    public List<Map<String, Object>> sessions() {
        Path sessions = namespace().resolve("sessions");
        if (!Files.isDirectory(sessions)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(sessions)) {
            return folders.map(folder -> folder.resolve("session.json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(file -> object(read(file)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Summary> latestSummary(String conversationId) {
        return files(namespace().resolve("conversations").resolve(conversationId).resolve("summaries"))
                .map(file -> object(read(file)))
                .filter(summary -> summary.get("content") instanceof String
                        && summary.get("toSequenceNum") instanceof Number)
                .max(Comparator.comparingLong(summary -> ((Number) summary.get("toSequenceNum")).longValue()))
                .map(summary -> new Summary((String) summary.get("content"),
                        ((Number) summary.get("toSequenceNum")).longValue()));
    }

    /** The JSON files of a folder, in name order; none for a folder that is not there. */
    private static Stream<Path> files(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Stream.empty();
        }
        try (Stream<Path> listing = Files.list(folder)) {
            return listing.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> object(String text) {
        try {
            return json.readValue(text, OBJECT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
