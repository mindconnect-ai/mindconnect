package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * File-based LLM call trace store.
 *
 * <p>Layout:
 * <pre>
 * {base}/conversations/{conversationId}/traces/
 *   {turnId}/
 *     {startedAtMs}-{traceId}.json
 * </pre>
 *
 * <p>Sorting by filename = sorting by start time, so listing a turn or
 * session is just a directory walk + filename-sort. Each trace is one
 * JSON document holding its full {@link LlmCallTrace} record (verbatim
 * provider request and response bodies included as embedded strings).
 *
 * <p><b>Retention:</b> after each save, traces beyond
 * {@link #maxTracesPerSession} (default 50) are deleted oldest-first
 * across all turns of the session, keeping the directory bounded.
 */
public class FileLlmCallTraceRepository implements LlmCallTraceRepository {

    private static final Logger log = LoggerFactory.getLogger(FileLlmCallTraceRepository.class);

    /**
     * Default per-session retention cap. Generous enough to debug long
     * conversations, small enough that the directory doesn't bloat for
     * heavy users. Tunable via constructor.
     */
    public static final int DEFAULT_MAX_PER_SESSION = 50;

    private final Path baseDir;
    private final int maxTracesPerSession;
    private final ObjectMapper mapper;

    public FileLlmCallTraceRepository(Path baseDir) {
        this(baseDir, DEFAULT_MAX_PER_SESSION);
    }

    public FileLlmCallTraceRepository(Path baseDir, int maxTracesPerSession) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.maxTracesPerSession = maxTracesPerSession;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void save(LlmCallTrace trace) {
        Path turnDir = turnDirFor(trace.context().conversationId(), trace.context().turnId());
        Path file = turnDir.resolve(filenameFor(trace));
        try {
            Files.createDirectories(turnDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), trace);
            log.debug("Saved LLM trace {} for turn {} ({} ms, {} prompt + {} completion tokens)",
                    trace.id(), trace.context().turnId(), trace.durationMs(),
                    trace.promptTokens(), trace.completionTokens());
            enforceRetention(trace.context().conversationId());
        } catch (IOException e) {
            log.warn("Failed to save LLM trace {} for turn {}: {}",
                    trace.id(), trace.context().turnId(), e.getMessage());
        }
    }

    @Override
    public List<LlmCallTrace> findByTurn(UUID turnId) {
        // Without conversationId we can't direct-lookup; scan all conversations.
        // Acceptable for an admin-debug feature; turns into a directory walk.
        List<LlmCallTrace> result = new ArrayList<>();
        Path conversationsDir = baseDir;
        if (!Files.isDirectory(conversationsDir)) return result;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(conversationsDir)) {
            for (Path conv : convs) {
                Path turnDir = conv.resolve("traces").resolve(turnId.toString());
                if (Files.isDirectory(turnDir)) {
                    result.addAll(loadTurnDir(turnDir));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for turn {}: {}", turnId, e.getMessage());
        }
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public List<LlmCallTrace> findDescendants(UUID rootTurnId) {
        // Walk every conversation once and group all traces by their parent
        // turnId. Then BFS down from {@code rootTurnId} to collect the whole
        // sub-tree. Cheaper than re-walking the directory per BFS level, and
        // keeps the file scan to a single pass for the typical "show all
        // sub-agent calls of this turn" query.
        java.util.Map<UUID, List<LlmCallTrace>> byParent = new java.util.HashMap<>();
        Path conversationsDir = baseDir;
        if (!Files.isDirectory(conversationsDir)) return List.of();
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(conversationsDir)) {
            for (Path conv : convs) {
                Path tracesRoot = conv.resolve("traces");
                if (!Files.isDirectory(tracesRoot)) continue;
                try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
                    for (Path turn : turns) {
                        for (LlmCallTrace t : loadTurnDir(turn)) {
                            UUID parent = t.context() != null ? t.context().parentTurnId() : null;
                            if (parent == null) continue;
                            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(t);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for descendants of {}: {}", rootTurnId, e.getMessage());
            return List.of();
        }

        List<LlmCallTrace> out = new ArrayList<>();
        java.util.ArrayDeque<UUID> frontier = new java.util.ArrayDeque<>();
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        frontier.add(rootTurnId);
        while (!frontier.isEmpty()) {
            UUID parent = frontier.poll();
            if (!visited.add(parent)) continue; // protect against pathological cycles
            List<LlmCallTrace> children = byParent.get(parent);
            if (children == null) continue;
            for (LlmCallTrace c : children) {
                out.add(c);
                if (c.context() != null && c.context().turnId() != null) {
                    frontier.add(c.context().turnId());
                }
            }
        }
        out.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return out;
    }

    @Override
    public List<LlmCallTrace> findByConversation(UUID conversationId) {
        Path tracesRoot = baseDir.resolve(conversationId.toString()).resolve("traces");
        if (!Files.isDirectory(tracesRoot)) return List.of();
        List<LlmCallTrace> result = new ArrayList<>();
        try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
            for (Path turn : turns) {
                if (Files.isDirectory(turn)) result.addAll(loadTurnDir(turn));
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for conversation {}: {}", conversationId, e.getMessage());
        }
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public List<LlmCallTrace> findBySession(UUID sessionId) {
        List<LlmCallTrace> result = new ArrayList<>();
        Path conversationsDir = baseDir;
        if (!Files.isDirectory(conversationsDir)) return result;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(conversationsDir)) {
            for (Path conv : convs) {
                Path tracesRoot = conv.resolve("traces");
                if (!Files.isDirectory(tracesRoot)) continue;
                try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
                    for (Path turn : turns) {
                        for (LlmCallTrace t : loadTurnDir(turn)) {
                            if (sessionId.equals(t.context().sessionId())) result.add(t);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for session {}: {}", sessionId, e.getMessage());
        }
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public Optional<LlmCallTrace> findById(UUID id) {
        Path conversationsDir = baseDir;
        if (!Files.isDirectory(conversationsDir)) return Optional.empty();
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(conversationsDir)) {
            for (Path conv : convs) {
                Path tracesRoot = conv.resolve("traces");
                if (!Files.isDirectory(tracesRoot)) continue;
                try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
                    for (Path turn : turns) {
                        for (LlmCallTrace t : loadTurnDir(turn)) {
                            if (id.equals(t.id())) return Optional.of(t);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for id {}: {}", id, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        // Walk every turn dir under every conversation, delete files whose
        // sessionId matches; remove now-empty turn directories.
        Path conversationsDir = baseDir;
        if (!Files.isDirectory(conversationsDir)) return;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(conversationsDir)) {
            for (Path conv : convs) {
                Path tracesRoot = conv.resolve("traces");
                if (!Files.isDirectory(tracesRoot)) continue;
                try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
                    for (Path turn : turns) {
                        boolean turnHadAny = false;
                        boolean turnNowEmpty = true;
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(turn, "*.json")) {
                            for (Path f : files) {
                                turnHadAny = true;
                                LlmCallTrace t = readQuietly(f);
                                if (t != null && sessionId.equals(t.context().sessionId())) {
                                    Files.deleteIfExists(f);
                                } else {
                                    turnNowEmpty = false;
                                }
                            }
                        }
                        if (turnHadAny && turnNowEmpty) {
                            try { Files.deleteIfExists(turn); } catch (IOException ignored) {}
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete traces for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Path turnDirFor(UUID conversationId, UUID turnId) {
        return baseDir.resolve(conversationId.toString()).resolve("traces").resolve(turnId.toString());
    }

    private String filenameFor(LlmCallTrace trace) {
        // Zero-padded epoch-ms keeps lexicographic order = chronological
        // order. UUID suffix prevents collisions when two calls land in
        // the same millisecond.
        return String.format("%013d-%s.json", trace.startedAt().toEpochMilli(), trace.id());
    }

    private List<LlmCallTrace> loadTurnDir(Path turnDir) {
        List<LlmCallTrace> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(turnDir, "*.json")) {
            for (Path f : files) {
                LlmCallTrace t = readQuietly(f);
                if (t != null) out.add(t);
            }
        } catch (IOException e) {
            log.warn("Failed to list traces under {}: {}", turnDir, e.getMessage());
        }
        return out;
    }

    private LlmCallTrace readQuietly(Path file) {
        try {
            return mapper.readValue(file.toFile(), LlmCallTrace.class);
        } catch (IOException e) {
            log.warn("Failed to read LLM trace {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * After a save, prune the oldest traces in this conversation if the
     * total goes beyond the cap. We use a per-conversation cap as a proxy
     * for per-session — typically there's one session per conversation in
     * the agent runtime.
     */
    private void enforceRetention(UUID conversationId) {
        if (maxTracesPerSession <= 0) return;
        Path tracesRoot = baseDir.resolve(conversationId.toString()).resolve("traces");
        if (!Files.isDirectory(tracesRoot)) return;

        // Collect every trace file across all turn dirs of this conversation.
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
            for (Path turn : turns) {
                if (!Files.isDirectory(turn)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(turn, "*.json")) {
                    for (Path f : files) all.add(f);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate traces for retention sweep ({}): {}", conversationId, e.getMessage());
            return;
        }

        if (all.size() <= maxTracesPerSession) return;

        // Lexicographic sort = chronological (filename starts with epoch-ms).
        all.sort(Comparator.comparing(p -> p.getFileName().toString()));
        int toDelete = all.size() - maxTracesPerSession;
        for (int i = 0; i < toDelete; i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException e) {
                log.warn("Failed to prune old trace {}: {}", all.get(i), e.getMessage());
            }
        }
        log.debug("Retention sweep pruned {} oldest trace(s) for conversation {}", toDelete, conversationId);
    }
}
