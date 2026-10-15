package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.CommandRunner;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Puts a session's files and commands on a virtual environment server.
 *
 * <p>The workspace is keyed by the template — the binding's {@code environment}
 * override, else the configured default — and the root session, so sub-agents
 * work in the files of the chat they serve. Files a user attached to the chat
 * live in the session's directory on this machine; before a tool works on the
 * workspace, new ones under {@code uploads/} are copied to {@code /workspace/uploads/}.
 *
 * <p>What the provider remembers per workspace — the running environment's id and
 * which uploads it copied — is dropped once the workspace has not been used for
 * the idle timeout (two hours by default); nothing tells the provider when a chat
 * is over. Dropping it costs nothing but a fresh acquire and one more copy of the
 * uploads. Stopping the environment is the server's business: it stops one that
 * is idle for its template's idle stop and deletes the workspace after the
 * template's retention. The timeout has to stay below that retention, or the
 * provider would take uploads for copied that went with the deleted workspace.
 */
public class VirtualEnvWorkspaceProvider implements WorkspaceProvider {

    private static final Logger log = LoggerFactory.getLogger(VirtualEnvWorkspaceProvider.class);

    /** The tool binding override naming the template, e.g. {@code {"environment": "office"}}. */
    public static final String ENVIRONMENT_OVERRIDE = "environment";
    static final String UPLOADS = "uploads";
    /** How long a workspace's state is kept without use, unless configured otherwise. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(2);

    private final VirtualEnvClient client;
    private final String defaultTemplate;
    private final Duration queueTimeout;
    private final ConcurrentMap<String, String> environmentIds = new ConcurrentHashMap<>();
    /** Uploads already copied, per workspace: relative name → size and modification time. */
    private final ConcurrentMap<String, Map<String, String>> mirrored = new ConcurrentHashMap<>();
    /** When each workspace was last asked for. */
    private final ConcurrentMap<String, Instant> lastUsed = new ConcurrentHashMap<>();
    private final Duration idleTimeout;
    private final Clock clock;
    /** When the maps were last swept for idle workspaces. */
    private volatile Instant lastSweep;

    public VirtualEnvWorkspaceProvider(VirtualEnvClient client, String defaultTemplate, Duration queueTimeout) {
        this(client, defaultTemplate, queueTimeout, DEFAULT_IDLE_TIMEOUT, Clock.systemUTC());
    }

    /**
     * @param idleTimeout how long the state of a workspace nobody uses is kept; stay below
     *                    the server's retention
     */
    public VirtualEnvWorkspaceProvider(VirtualEnvClient client, String defaultTemplate, Duration queueTimeout,
                                       Duration idleTimeout, Clock clock) {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("The idle timeout must be positive: " + idleTimeout);
        }
        this.client = client;
        this.defaultTemplate = defaultTemplate;
        this.queueTimeout = queueTimeout;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    @Override
    public WorkspaceFiles files(ToolCallScope scope, AgentTool tool, FileRoots localRoots) {
        WorkspaceKey key = key(scope, tool);
        used(key);
        mirrorUploads(scope, key);
        return new RemoteWorkspaceFiles(client, key, localDir(scope));
    }

    @Override
    public Optional<CommandRunner> commands(ToolCallScope scope, AgentTool tool) {
        WorkspaceKey key = key(scope, tool);
        used(key);
        mirrorUploads(scope, key);
        Path local = localDir(scope);
        return Optional.of(new RemoteCommandRunner(client, key, environmentIds, queueTimeout,
                local == null ? null : local.toString()));
    }

    /** The session's directory on this machine, which the prompt names; {@code null} without one. */
    private static Path localDir(ToolCallScope scope) {
        return scope == null || !scope.hasWorkingDir() ? null : Path.of(scope.workingDir()).toAbsolutePath().normalize();
    }

    WorkspaceKey key(ToolCallScope scope, AgentTool tool) {
        Object override = tool == null ? null : tool.overrides().get(ENVIRONMENT_OVERRIDE);
        String template = override == null || String.valueOf(override).isBlank()
                ? defaultTemplate : String.valueOf(override).strip();
        String sessionKey;
        if (scope != null && scope.rootSessionId() != null) {
            sessionKey = scope.rootSessionId().value();
        } else if (scope != null && scope.sessionId() != null) {
            sessionKey = scope.sessionId().value();
        } else if (scope != null && scope.userId() != null) {
            sessionKey = "user-" + scope.userId().value();
        } else {
            sessionKey = "shared";
        }
        return new WorkspaceKey(template, sessionKey, scope);
    }

    /**
     * Copies files under the session's local {@code uploads/} that the
     * workspace has not seen yet — or has seen with another size or time.
     * Failures are logged, not thrown: a tool call should not fail because an
     * attachment could not be mirrored.
     */
    void mirrorUploads(ToolCallScope scope, WorkspaceKey key) {
        if (scope == null || !scope.hasWorkingDir()) {
            return;
        }
        Path uploads = Path.of(scope.workingDir()).resolve(UPLOADS);
        if (!Files.isDirectory(uploads)) {
            return;
        }
        Map<String, String> seen = mirrored.computeIfAbsent(key.id(), id -> new ConcurrentHashMap<>());
        try (Stream<Path> files = Files.walk(uploads)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = UPLOADS + "/" + uploads.relativize(file).toString().replace('\\', '/');
                String stamp = Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
                if (stamp.equals(seen.get(relative))) {
                    continue;
                }
                client.write(key, relative, Files.readAllBytes(file));
                seen.put(relative, stamp);
                log.info("Copied upload {} to workspace {}", relative, key.id());
            }
        } catch (IOException e) {
            log.warn("Copying uploads of {} to the workspace failed: {}", key.id(), e.getMessage());
        }
    }

    /**
     * Marks the workspace as used now, and — at most every tenth of the idle timeout —
     * forgets the workspaces nobody used for the idle timeout. Swept on use rather than
     * by a thread of its own: while nothing is used, nothing is added either.
     */
    private void used(WorkspaceKey key) {
        Instant now = clock.instant();
        lastUsed.put(key.id(), now);
        if (Duration.between(lastSweep, now).compareTo(idleTimeout.dividedBy(10)) >= 0) {
            lastSweep = now;
            sweep(now);
        }
    }

    /** Forgets every workspace unused since {@code now} minus the idle timeout. */
    void sweep(Instant now) {
        Instant cutoff = now.minus(idleTimeout);
        for (Map.Entry<String, Instant> entry : Set.copyOf(lastUsed.entrySet())) {
            if (entry.getValue().isBefore(cutoff) && lastUsed.remove(entry.getKey(), entry.getValue())) {
                environmentIds.remove(entry.getKey());
                mirrored.remove(entry.getKey());
                log.debug("Forgot idle workspace {}", entry.getKey());
            }
        }
    }

    /** How many workspaces the provider currently remembers anything about. */
    int remembered() {
        Set<String> ids = new java.util.HashSet<>(lastUsed.keySet());
        ids.addAll(environmentIds.keySet());
        ids.addAll(mirrored.keySet());
        return ids.size();
    }

    /** Forgets what this provider knows about the workspaces of a session. */
    public void forget(String rootSessionId) {
        Set.copyOf(environmentIds.keySet()).stream().filter(id -> id.endsWith("/" + rootSessionId))
                .forEach(environmentIds::remove);
        Set.copyOf(mirrored.keySet()).stream().filter(id -> id.endsWith("/" + rootSessionId))
                .forEach(mirrored::remove);
        Set.copyOf(lastUsed.keySet()).stream().filter(id -> id.endsWith("/" + rootSessionId))
                .forEach(lastUsed::remove);
    }
}
