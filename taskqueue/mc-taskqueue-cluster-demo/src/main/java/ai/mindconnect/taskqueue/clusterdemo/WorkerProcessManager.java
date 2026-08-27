package ai.mindconnect.taskqueue.clusterdemo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The master's process manager: workers are real OS processes — the same jar,
 * started with {@code --mindconnect.cluster.role=worker} and their own port —
 * watched via {@link ProcessHandle} and restarted when they die without being
 * told to. "Kill" exists on purpose: killing a worker mid-crawl is THE demo
 * of the lease — the store hands its running task to a surviving node.
 *
 * <p>Monitoring here is an ops view and a restart policy, nothing more. The
 * CORRECTNESS story of a dead worker is the lease in the store; this class
 * could be wrong about liveness all day and no task would be lost.
 */
@Component
public class WorkerProcessManager {

    /** One managed worker process, as the ops panel sees it. */
    public record ManagedWorker(int port, long pid, String status, int restarts, Instant startedAt) { }

    private static final Logger log = LoggerFactory.getLogger(WorkerProcessManager.class);

    private final ClusterProperties cluster;
    private final ClusterHttp http;
    private final Environment environment;
    private final Map<Integer, Handle> workers = new ConcurrentSkipListMap<>();
    private final AtomicInteger nextPort = new AtomicInteger();
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean open = true;

    private static final class Handle {
        volatile Process process;
        volatile boolean desired = true;    // false = stopped on purpose, no restart
        volatile boolean crashLooping;      // gave up restarting — the slot is broken
        volatile int restarts;
        volatile int fastExitsInARow;       // died within seconds of starting
        volatile Instant startedAt;
    }

    public WorkerProcessManager(ClusterProperties cluster, ClusterHttp http, Environment environment) {
        this.cluster = cluster;
        this.http = http;
        this.environment = environment;
        this.nextPort.set(cluster.workerBasePort());
    }

    /** Fired after every lifecycle change — start, exit, stop, kill, restart. */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        changeListeners.forEach(listener -> {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("Worker change listener failed: {}", e.getMessage());
            }
        });
    }

    @PostConstruct
    void startInitialWorkers() {
        if (!cluster.isMaster()) return;
        for (int i = 0; i < cluster.workers(); i++) {
            startWorker();
        }
    }

    /** Starts one more worker on the next free port and returns it. */
    public synchronized int startWorker() {
        int port = nextPort.getAndIncrement();
        Handle handle = new Handle();
        workers.put(port, handle);
        launch(port, handle);
        return port;
    }

    /** Graceful stop, no restart — the operator said so. */
    public boolean stopWorker(int port) {
        Handle handle = workers.get(port);
        if (handle == null) return false;
        handle.desired = false;
        if (handle.process != null) handle.process.destroy();
        fireChanged();
        return true;
    }

    /**
     * SIGKILL — no shutdown hooks, no goodbye. The task the worker was
     * running keeps its lease until it expires; then the store hands it on.
     */
    public boolean killWorker(int port) {
        Handle handle = workers.get(port);
        if (handle == null) return false;
        handle.desired = false;
        if (handle.process != null) handle.process.destroyForcibly();
        fireChanged();
        return true;
    }

    /** Re-arms a stopped/killed worker slot. */
    public boolean restartWorker(int port) {
        Handle handle = workers.get(port);
        if (handle == null || (handle.process != null && handle.process.isAlive())) return false;
        handle.desired = true;
        handle.crashLooping = false;
        handle.fastExitsInARow = 0;
        handle.restarts++;
        launch(port, handle);
        return true;
    }

    /**
     * Forgets a worker SLOT — only one that is down and meant to stay down.
     * A running worker is stopped first, on purpose in two steps: stopping is
     * an action with consequences (its tasks hand over), deleting is
     * bookkeeping, and one button should never do both.
     */
    public boolean deleteWorker(int port) {
        Handle handle = workers.get(port);
        if (handle == null) return false;
        if (handle.desired || (handle.process != null && handle.process.isAlive())) {
            return false;   // still running or set to restart — stop it first
        }
        workers.remove(port);
        fireChanged();
        return true;
    }

    public List<ManagedWorker> list() {
        List<ManagedWorker> result = new ArrayList<>();
        workers.forEach((port, handle) -> {
            Process process = handle.process;
            boolean alive = process != null && process.isAlive();
            String status = alive ? "UP"
                    : handle.crashLooping ? "CRASHED"
                    : handle.desired ? "RESTARTING" : "STOPPED";
            result.add(new ManagedWorker(port, alive ? process.pid() : -1,
                    status, handle.restarts, handle.startedAt));
        });
        return result;
    }

    /** "The queue changed — look now", fanned out to every live worker. */
    public void nudgeWorkers() {
        workers.forEach((port, handle) -> {
            if (handle.process != null && handle.process.isAlive()) {
                http.nudge("http://localhost:" + port, "/cluster/nudge");
            }
        });
    }

    private void launch(int port, Handle handle) {
        try {
            List<String> command = buildCommand(port);
            Path logDir = Path.of("logs");
            Files.createDirectories(logDir);
            File logFile = logDir.resolve("worker-" + port + ".log").toFile();
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectErrorStream(true)
                    .start();
            handle.process = process;
            handle.startedAt = Instant.now();
            log.info("Started worker :{} (pid {}) → {}", port, process.pid(), logFile);
            fireChanged();
            process.onExit().thenAccept(ended -> {
                log.warn("Worker :{} exited with {}", port, ended.exitValue());
                boolean diedYoung = handle.startedAt != null
                        && Instant.now().isBefore(handle.startedAt.plusSeconds(5));
                handle.fastExitsInARow = diedYoung ? handle.fastExitsInARow + 1 : 0;
                if (handle.fastExitsInARow >= 5) {
                    // The port is taken, the config is broken — restarting per
                    // second forever only hides it. Give up and say so.
                    handle.desired = false;
                    handle.crashLooping = true;
                    log.error("Worker :{} crash-loops (5 immediate exits) — giving up; "
                            + "see logs/worker-{}.log", port, port);
                }
                fireChanged();
                if (open && handle.desired) {
                    handle.restarts++;
                    log.info("Restarting worker :{} (restart #{})", port, handle.restarts);
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(2000);   // backoff, not a hot loop
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (open && handle.desired) launch(port, handle);
                    });
                }
            });
        } catch (IOException e) {
            log.error("Cannot start worker :{} — {}", port, e.getMessage());
        }
    }

    /**
     * The same application, as a child process. Packaged: {@code java -jar
     * <this jar>}. In dev (spring-boot:run, exploded classes): {@code java -cp
     * <inherited classpath> <main class>} — both carry the same arguments.
     */
    private List<String> buildCommand(int port) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        String classPath = System.getProperty("java.class.path");
        if (!classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")) {
            // Started as `java -jar app.jar` — the child starts the same way.
            command.add("-jar");
            command.add(classPath);
        } else {
            // Dev mode (spring-boot:run, exploded classes): inherit the classpath.
            command.add("-cp");
            command.add(classPath);
            command.add(ClusterDemoApplication.class.getName());
        }
        command.add("--mindconnect.cluster.role=worker");
        command.add("--server.port=" + port);
        command.add("--mindconnect.cluster.master-url=http://localhost:" + cluster.port());
        command.add("--mindconnect.cluster.worker-concurrency=" + cluster.workerConcurrency());
        for (String key : List.of("mindconnect.cluster.db.url",
                "mindconnect.cluster.db.user", "mindconnect.cluster.db.password",
                "mindconnect.cluster.output-root")) {
            String value = environment.getProperty(key);
            if (value != null) command.add("--" + key + "=" + value);
        }
        return command;
    }

    @PreDestroy
    void shutdown() {
        open = false;
        workers.forEach((port, handle) -> {
            if (handle.process != null) handle.process.destroy();
        });
    }
}
