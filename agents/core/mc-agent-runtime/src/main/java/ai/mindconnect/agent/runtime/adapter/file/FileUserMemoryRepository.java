package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;
import ai.mindconnect.common.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Keeps each memory entry as a Markdown file with a front matter, the way
 * Claude Code keeps its auto memory:
 *
 * <pre>
 * {base}/{namespace}/memory/{user}/{name}.md
 *
 * ---
 * name: preferred-language
 * description: Answers in German, code comments in English
 * type: feedback
 * created: 2026-09-23T10:00:00Z
 * updated: 2026-09-23T10:00:00Z
 * source: 3f2c…
 * ---
 *
 * The content.
 * </pre>
 *
 * <p>The user part is URL-encoded, since a user id may be an e-mail address;
 * the name is already safe as a file name ({@code UserMemoryService}
 * normalises it). A file written by hand needs only {@code description} and
 * {@code type} — the name defaults to the file name, and a {@code type}
 * nested under {@code metadata:}, as Claude Code writes it, is read too.
 */
public class FileUserMemoryRepository implements UserMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileUserMemoryRepository.class);
    private static final String SUFFIX = ".md";
    private static final String FENCE = "---";

    private final Path baseDir;

    public FileUserMemoryRepository(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).resolve("memory").toAbsolutePath().normalize();
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) return List.of();
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(SUFFIX) && !f.getFileName().toString().startsWith("."))
                    .forEach(f -> read(userId, f).ifPresent(entries::add));
        } catch (IOException e) {
            log.warn("Failed to list memory of user {}: {}", userId, e.getMessage());
        }
        return entries;
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, String name) {
        Path file = fileFor(userId, name);
        return Files.exists(file) ? read(userId, file) : Optional.empty();
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        Path file = fileFor(entry.userId(), entry.name());
        try {
            AtomicFiles.write(file, out -> out.write(render(entry).getBytes(StandardCharsets.UTF_8)));
            return entry;
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist memory '" + entry.name() + "'", e);
        }
    }

    @Override
    public boolean delete(UserId userId, String name) {
        try {
            return Files.deleteIfExists(fileFor(userId, name));
        } catch (IOException e) {
            log.warn("Failed to delete memory '{}' of user {}: {}", name, userId, e.getMessage());
            return false;
        }
    }

    static String render(MemoryEntry e) {
        StringBuilder out = new StringBuilder(FENCE).append('\n')
                .append("name: ").append(e.name()).append('\n')
                .append("description: ").append(e.description()).append('\n')
                .append("type: ").append(e.type().wireName()).append('\n')
                .append("created: ").append(e.createdAt()).append('\n')
                .append("updated: ").append(e.updatedAt()).append('\n');
        if (e.sourceSessionId() != null) out.append("source: ").append(e.sourceSessionId().value()).append('\n');
        return out.append(FENCE).append("\n\n").append(e.content()).append('\n').toString();
    }

    static Optional<MemoryEntry> parse(UserId userId, String fileName, String text) {
        String normalised = text.replace("\r\n", "\n");
        if (!normalised.startsWith(FENCE + "\n")) return Optional.empty();
        int end = normalised.indexOf("\n" + FENCE, FENCE.length());
        if (end < 0) return Optional.empty();
        Map<String, String> fields = new HashMap<>();
        for (String line : normalised.substring(FENCE.length() + 1, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String value = line.substring(colon + 1).strip();
            if (!value.isEmpty()) fields.putIfAbsent(line.substring(0, colon).strip(), value);
        }
        int bodyStart = normalised.indexOf('\n', end + 1);
        String content = bodyStart < 0 ? "" : normalised.substring(bodyStart + 1).strip();
        String name = fields.getOrDefault("name", fileName.substring(0, fileName.length() - SUFFIX.length()));
        Instant updated = instant(fields.get("updated"));
        return Optional.of(new MemoryEntry(userId, name,
                MemoryType.parse(fields.getOrDefault("type", "user")),
                fields.get("description"), content,
                fields.containsKey("source") ? SessionId.of(fields.get("source")) : null,
                instant(fields.get("created")), updated));
    }

    private Optional<MemoryEntry> read(UserId userId, Path file) {
        try {
            String fileName = file.getFileName().toString();
            Optional<MemoryEntry> entry = parse(userId, fileName, Files.readString(file));
            if (entry.isEmpty()) log.warn("Memory file {} has no front matter — skipped", file);
            // The file name is the key: an entry renamed by hand inside the file is still found by its file.
            return entry.map(e -> e.name().equals(stripSuffix(fileName)) ? e
                    : new MemoryEntry(e.userId(), stripSuffix(fileName), e.type(), e.description(), e.content(),
                            e.sourceSessionId(), e.createdAt(), e.updatedAt()));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read memory file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - SUFFIX.length());
    }

    private Path userDir(UserId userId) {
        // URLEncoder leaves dots alone: "." and ".." must not name a directory.
        String dir = URLEncoder.encode(userId.value(), StandardCharsets.UTF_8);
        if (dir.chars().allMatch(c -> c == '.')) dir = dir.replace(".", "%2E");
        return baseDir.resolve(dir);
    }

    private Path fileFor(UserId userId, String name) {
        Path file = userDir(userId).resolve(name + SUFFIX).normalize();
        if (!file.startsWith(userDir(userId))) throw new IllegalArgumentException("invalid memory name: " + name);
        return file;
    }
}
