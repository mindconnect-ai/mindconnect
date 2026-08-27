package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tools.code.CodeLanguages.CodeLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Session-scoped container execution: the first call for a (session, language)
 * pair starts a long-lived idle container, every further call is a plain
 * {@code exec} into it — warm-start latency without a container pool. State
 * persists per session the way the Anthropic code-execution tool's
 * {@code container.id} does: files under {@code /workspace} and installed
 * packages survive between calls; a fresh interpreter process runs each time.
 *
 * <p>Isolation per container: no network, memory/cpu limits, and a bind mount
 * of one session-private scratch directory as the only host filesystem access.
 * Idle containers are reaped after a timeout; everything carries a label so
 * containers from a crashed previous run are cleaned up at startup.
 */
public final class CodeExecutionService implements AutoCloseable {

    /** Label stamped on every container this service starts. */
    static final String LABEL = "ai.mindconnect.code-exec";

    public record Settings(String memory, String cpus, Duration execTimeout,
                           Duration idleTimeout, Path scratchRoot) {
        public static Settings defaults(Path scratchRoot) {
            return new Settings("512m", "1", Duration.ofSeconds(60), Duration.ofMinutes(10), scratchRoot);
        }
    }

    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut, long tookMs) {}

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);

    /** Generous ceiling for container lifecycle commands (image pull included). */
    private static final Duration LIFECYCLE_TIMEOUT = Duration.ofMinutes(5);

    private static final class Session {
        final String containerId;
        volatile long lastUsedMs;

        Session(String containerId) {
            this.containerId = containerId;
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    private final ContainerCli cli;
    private final Settings settings;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public CodeExecutionService(ContainerCli cli, Settings settings) {
        this.cli = cli;
        this.settings = settings;
        removeStaleContainers();
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "code-exec-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleAtFixedRate(this::reapIdle, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Runs {@code code} in the session's container for {@code language},
     * creating the container on first use. {@code network} is a container
     * network mode ({@code none} or {@code bridge}) — part of the session key,
     * because it is fixed at container creation. A timed-out execution kills
     * the container (the runaway process would otherwise keep burning its CPU
     * budget); the next call starts fresh.
     */
    public ExecResult execute(String sessionKey, CodeLanguage language, String network, String code) {
        String key = sessionKey + ":" + language.name() + ":" + network;
        Session session = sessions.computeIfAbsent(key, k -> start(sessionKey, language, network));
        long begin = System.currentTimeMillis();
        ContainerCli.Result result = exec(session, language, code);
        if (containerGone(result)) {
            // Removed behind our back (manual prune, engine restart) — one retry
            // with a fresh container.
            sessions.remove(key, session);
            session = sessions.computeIfAbsent(key, k -> start(sessionKey, language, network));
            begin = System.currentTimeMillis();
            result = exec(session, language, code);
        }
        long took = System.currentTimeMillis() - begin;
        session.lastUsedMs = System.currentTimeMillis();
        if (result.timedOut()) {
            sessions.remove(key, session);
            remove(session.containerId);
        }
        return new ExecResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(), took);
    }

    private ContainerCli.Result exec(Session session, CodeLanguage language, String code) {
        List<String> args = new ArrayList<>(List.of("exec", "-i", session.containerId));
        args.addAll(language.command());
        return cli.run(settings.execTimeout(), code, args.toArray(String[]::new));
    }

    private Session start(String sessionKey, CodeLanguage language, String network) {
        Path scratch = scratchDir(sessionKey);
        ContainerCli.Result result = cli.run(LIFECYCLE_TIMEOUT, null,
                "run", "-d",
                "--label", LABEL + "=1",
                "--network", network,
                "--memory", settings.memory(),
                "--cpus", settings.cpus(),
                "--workdir", "/workspace",
                "-v", scratch.toAbsolutePath() + ":/workspace",
                language.image(),
                // Portable idle keep-alive: busybox sleep has no "infinity".
                "sleep", "2147483647");
        if (!result.ok()) {
            throw new IllegalStateException("Could not start " + language.name() + " container ("
                    + cli.binary() + " run failed): " + result.stderr().trim());
        }
        String containerId = result.stdout().trim();
        log.info("Started code-exec container {} ({} / {})", shortId(containerId), sessionKey, language.name());
        return new Session(containerId);
    }

    private Path scratchDir(String sessionKey) {
        Path dir = settings.scratchRoot().resolve(sessionKey);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create code-exec scratch dir " + dir, e);
        }
        return dir;
    }

    private static boolean containerGone(ContainerCli.Result result) {
        if (result.ok() || result.timedOut()) {
            return false;
        }
        String stderr = result.stderr().toLowerCase();
        return stderr.contains("no such container") || stderr.contains("is not running")
                || stderr.contains("container state improper");
    }

    /** Kills sessions whose last use is older than the idle timeout. */
    void reapIdle() {
        long cutoff = System.currentTimeMillis() - settings.idleTimeout().toMillis();
        sessions.forEach((key, session) -> {
            if (session.lastUsedMs < cutoff && sessions.remove(key, session)) {
                log.info("Reaping idle code-exec container {}", shortId(session.containerId));
                remove(session.containerId);
            }
        });
    }

    /** Containers left over from a crashed previous run, found by label. */
    private void removeStaleContainers() {
        try {
            ContainerCli.Result result = cli.run(Duration.ofSeconds(30), null,
                    "ps", "-aq", "--filter", "label=" + LABEL);
            for (String id : result.stdout().split("\\R")) {
                if (!id.isBlank()) {
                    log.info("Removing stale code-exec container {}", shortId(id.trim()));
                    remove(id.trim());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Stale code-exec container cleanup failed: {}", e.toString());
        }
    }

    private void remove(String containerId) {
        try {
            cli.run(Duration.ofSeconds(30), null, "rm", "-f", containerId);
        } catch (RuntimeException e) {
            log.warn("Could not remove code-exec container {}: {}", shortId(containerId), e.toString());
        }
    }

    private static String shortId(String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    @Override
    public void close() {
        reaper.shutdownNow();
        sessions.forEach((key, session) -> remove(session.containerId));
        sessions.clear();
    }
}
