package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.runtime.domain.TraceId;

import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
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

    public FileLlmCallTraceRepository(Path baseDir, Namespace namespace) {
        this(baseDir, DEFAULT_MAX_PER_SESSION, namespace);
    }

    public FileLlmCallTraceRepository(Path baseDir, int maxTracesPerSession, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).resolve("conversations").toAbsolutePath().normalize();
        this.maxTracesPerSession = maxTracesPerSession;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void save(LlmCallTrace trace) {
        Path turnDir = turnDirFor(trace.context().conversationId(), trace.context().turnId());
        Path file = turnDir.resolve(filenameFor(trace));
        try {
            Files.createDirectories(turnDir);
            AtomicFiles.write(file, out -> mapper.writerWithDefaultPrettyPrinter().writeValue(out, trace));
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
    public List<LlmCallTrace> findByTurn(ChatTurnId turnId) {
        List<LlmCallTrace> result = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return result;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(baseDir)) {
            for (Path conv : convs) {
                Path turnDir = conv.resolve("traces").resolve(turnId.value());
                if (Files.isDirectory(turnDir)) {
                    result.addAll(loadTurnDir(turnDir));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for turn {}: {}", turnId, e.getMessage());
        }
        result.removeIf(t -> !turnId.equals(t.context().turnId()));
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public List<LlmCallTrace> findDescendants(ChatTurnId rootTurnId) {
        java.util.Map<ChatTurnId, List<LlmCallTrace>> byParent = new java.util.HashMap<>();
        for (LlmCallTrace t : allTraces()) {
            ChatTurnId parent = t.context() != null ? t.context().parentTurnId() : null;
            if (parent == null) continue;
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(t);
        }
        List<LlmCallTrace> out = new ArrayList<>();
        java.util.ArrayDeque<ChatTurnId> frontier = new java.util.ArrayDeque<>();
        java.util.Set<ChatTurnId> visited = new java.util.HashSet<>();
        frontier.add(rootTurnId);
        while (!frontier.isEmpty()) {
            ChatTurnId parent = frontier.poll();
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
    public List<LlmCallTrace> findByConversation(ConversationId conversationId) {
        Path tracesRoot = baseDir.resolve(conversationId.value()).resolve("traces");
        if (!Files.isDirectory(tracesRoot)) return List.of();
        List<LlmCallTrace> result = new ArrayList<>();
        try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
            for (Path turn : turns) {
                if (Files.isDirectory(turn)) result.addAll(loadTurnDir(turn));
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces for conversation {}: {}", conversationId, e.getMessage());
        }
        result.removeIf(t -> !conversationId.equals(t.context().conversationId()));
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public List<LlmCallTrace> findBySession(SessionId sessionId) {
        List<LlmCallTrace> result = new ArrayList<>(allTraces().stream()
                .filter(t -> sessionId.equals(t.context().sessionId()))
                .toList());
        result.sort(Comparator.comparing(LlmCallTrace::startedAt));
        return result;
    }

    @Override
    public Optional<LlmCallTrace> findById(TraceId id) {
        return allTraces().stream().filter(t -> id.equals(t.id())).findFirst();
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        if (!Files.isDirectory(baseDir)) return;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(baseDir)) {
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

    // ── layout: <conversation>/traces/<turn>/<startedAt>-<id>.json ──────────

    private Path turnDirFor(ConversationId conversationId, ChatTurnId turnId) {
        return baseDir.resolve(conversationId.value()).resolve("traces").resolve(turnId.value());
    }

    private String filenameFor(LlmCallTrace trace) {
        return String.format("%013d-%s.json", trace.startedAt().toEpochMilli(), trace.id().value());
    }

    /** Every trace under every conversation, read in {@code namespace}. */
    private List<LlmCallTrace> allTraces() {
        List<LlmCallTrace> out = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return out;
        try (DirectoryStream<Path> convs = Files.newDirectoryStream(baseDir)) {
            for (Path conv : convs) {
                Path tracesRoot = conv.resolve("traces");
                if (!Files.isDirectory(tracesRoot)) continue;
                try (DirectoryStream<Path> turns = Files.newDirectoryStream(tracesRoot)) {
                    for (Path turn : turns) {
                        if (Files.isDirectory(turn)) out.addAll(loadTurnDir(turn));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan traces: {}", e.getMessage());
        }
        return out;
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

    /** A trace written before the namespace was recorded takes the one asked for. */
    private LlmCallTrace readQuietly(Path file) {
        try {
            return mapper.readerFor(LlmCallTrace.class)
                    .readValue(file.toFile());
        } catch (IOException e) {
            log.warn("Failed to read LLM trace {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void enforceRetention(ConversationId conversationId) {
        if (maxTracesPerSession <= 0) return;
        Path tracesRoot = baseDir.resolve(conversationId.value()).resolve("traces");
        if (!Files.isDirectory(tracesRoot)) return;
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
