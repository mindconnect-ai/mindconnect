package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.AgentId;
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
import java.net.URLDecoder;
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
 * {base}/{namespace}/memory/{user}/{name}.md                    — the user's own
 * {base}/{namespace}/memory/{user}/agents/{agent}/{name}.md     — one agent's about the user
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
 * <p>The user and agent parts are URL-encoded, since a user id may be an
 * e-mail address; which agent an entry belongs to is its directory, not a
 * field of the file;
 * the name is already safe as a file name ({@code UserMemoryService}
 * normalises it). A file written by hand needs only {@code description} and
 * {@code type} — the name defaults to the file name, and a {@code type}
 * nested under {@code metadata:}, as Claude Code writes it, is read too.
 */
public class FileUserMemoryRepository implements UserMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileUserMemoryRepository.class);
    private static final String SUFFIX = ".md";
    private static final String FENCE = "---";
    /** Under a user's directory: one directory per agent that keeps a memory of its own about them. */
    private static final String AGENTS = "agents";

    private final Path baseDir;

    public FileUserMemoryRepository(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).resolve("memory").toAbsolutePath().normalize();
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        List<MemoryEntry> entries = new ArrayList<>();
        Path dir = userDir(userId);
        readAll(userId, null, dir, entries);
        for (Path agentDir : directories(dir.resolve(AGENTS))) {
            AgentId agentId = AgentId.of(decode(agentDir.getFileName().toString()));
            readAll(userId, agentId, agentDir, entries);
        }
        return entries;
    }

    @Override
    public List<MemoryEntry> findAll() {
        List<MemoryEntry> entries = new ArrayList<>();
        for (Path dir : directories(baseDir)) {
            entries.addAll(findByUser(UserId.of(decode(dir.getFileName().toString()))));
        }
        return entries;
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name) {
        Path file = fileFor(userId, agentId, name);
        return Files.exists(file) ? read(userId, agentId, file) : Optional.empty();
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        Path file = fileFor(entry.userId(), entry.agentId(), entry.name());
        try {
            AtomicFiles.write(file, out -> out.write(render(entry).getBytes(StandardCharsets.UTF_8)));
            return entry;
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist memory '" + entry.name() + "'", e);
        }
    }

    @Override
    public boolean delete(UserId userId, AgentId agentId, String name) {
        try {
            return Files.deleteIfExists(fileFor(userId, agentId, name));
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
        return parse(userId, null, fileName, text);
    }

    static Optional<MemoryEntry> parse(UserId userId, AgentId agentId, String fileName, String text) {
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
        return Optional.of(new MemoryEntry(userId, agentId, name,
                MemoryType.parse(fields.getOrDefault("type", "user")),
                fields.get("description"), content,
                fields.containsKey("source") ? SessionId.of(fields.get("source")) : null,
                instant(fields.get("created")), updated));
    }

    private void readAll(UserId userId, AgentId agentId, Path dir, List<MemoryEntry> into) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(SUFFIX) && !f.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .forEach(f -> read(userId, agentId, f).ifPresent(into::add));
        } catch (IOException e) {
            log.warn("Failed to list memory in {}: {}", dir, e.getMessage());
        }
    }

    private static List<Path> directories(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private Optional<MemoryEntry> read(UserId userId, AgentId agentId, Path file) {
        try {
            String fileName = file.getFileName().toString();
            Optional<MemoryEntry> entry = parse(userId, agentId, fileName, Files.readString(file));
            if (entry.isEmpty()) log.warn("Memory file {} has no front matter — skipped", file);
            // The file name is the key: an entry renamed by hand inside the file is still found by its file.
            return entry.map(e -> e.name().equals(stripSuffix(fileName)) ? e
                    : new MemoryEntry(e.userId(), e.agentId(), stripSuffix(fileName), e.type(), e.description(), e.content(),
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
        return baseDir.resolve(encode(userId.value()));
    }

    /** The directory of the user's own memory, or of one agent's about them. */
    private Path memoryDir(UserId userId, AgentId agentId) {
        Path user = userDir(userId);
        return agentId == null ? user : user.resolve(AGENTS).resolve(encode(agentId.value()));
    }

    private Path fileFor(UserId userId, AgentId agentId, String name) {
        Path dir = memoryDir(userId, agentId);
        Path file = dir.resolve(name + SUFFIX).normalize();
        if (!file.startsWith(dir) || !file.getParent().equals(dir)) {
            throw new IllegalArgumentException("invalid memory name: " + name);
        }
        return file;
    }

    /** URL-encoded, and never "." or "..": URLEncoder leaves dots alone. */
    private static String encode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded.chars().allMatch(c -> c == '.') ? encoded.replace(".", "%2E") : encoded;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
