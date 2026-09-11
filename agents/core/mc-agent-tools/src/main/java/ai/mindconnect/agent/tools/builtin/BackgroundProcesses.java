package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * The processes {@code bash} left running in the background, per session:
 * a dev server, a watcher, a long build. Each writes to a log file the
 * model reads; {@code process_kill} ends one, and every one still alive
 * when the JVM stops is killed with it — nothing outlives the runtime.
 *
 * <p>Killing means the whole tree: {@code bash -c "npm run dev"} is a
 * shell, npm and node hang under it, and ending the shell alone leaves the
 * server bound to its port.
 */
final class BackgroundProcesses {

    private static final Logger log = LoggerFactory.getLogger(BackgroundProcesses.class);

    /** One background process: what was started, where it logs, its handle. */
    record Entry(long pid, SessionId sessionId, String command, Path log, Instant started, ProcessHandle handle) {
        boolean alive() {
            return handle.isAlive();
        }

        String describe() {
            return "pid " + pid + (alive() ? " (running)" : " (exited)") + ": " + command + " — log " + log;
        }
    }

    private static final Map<String, List<Entry>> BY_SESSION = new ConcurrentHashMap<>();
    private static final String NO_SESSION = "";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BackgroundProcesses::killAll, "bash-background-cleanup"));
    }

    private BackgroundProcesses() {}

    static Entry register(SessionId sessionId, Process process, String command, Path logFile) {
        Entry entry = new Entry(process.pid(), sessionId, command, logFile, Instant.now(), process.toHandle());
        BY_SESSION.computeIfAbsent(key(sessionId), k -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    /** This session's background processes, started first; exited ones stay listed until killed. */
    static List<Entry> list(SessionId sessionId) {
        return List.copyOf(BY_SESSION.getOrDefault(key(sessionId), List.of()));
    }

    static Optional<Entry> find(SessionId sessionId, long pid) {
        return list(sessionId).stream().filter(e -> e.pid() == pid).findFirst();
    }

    /** Kills the process and everything under it, and forgets it. */
    static boolean kill(SessionId sessionId, long pid) {
        Optional<Entry> entry = find(sessionId, pid);
        if (entry.isEmpty()) return false;
        killTree(entry.get().handle());
        BY_SESSION.getOrDefault(key(sessionId), List.of()).remove(entry.get());
        return true;
    }

    /** Ends every background process of every session — the JVM is going down. */
    static void killAll() {
        BY_SESSION.values().stream().flatMap(List::stream).filter(Entry::alive).forEach(e -> {
            log.info("bash: killing background process {} ({}) on shutdown", e.pid(), e.command());
            killTree(e.handle());
        });
        BY_SESSION.clear();
    }

    /**
     * Ends a process and all of its descendants — children first, so a
     * shell's death does not orphan the server it started.
     */
    static void killTree(ProcessHandle root) {
        try (Stream<ProcessHandle> descendants = root.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        root.destroyForcibly();
    }

    private static String key(SessionId sessionId) {
        return sessionId == null ? NO_SESSION : sessionId.value();
    }
}
