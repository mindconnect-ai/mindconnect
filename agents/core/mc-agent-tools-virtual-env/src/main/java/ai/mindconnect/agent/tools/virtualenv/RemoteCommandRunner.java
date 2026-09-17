package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.tool.workspace.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Runs commands in the session's environment on the server: acquires it, waits
 * while it is queued or starting, then execs. The environment id is shared per
 * workspace, so the next command goes straight to exec; a {@code 409} (the
 * environment was stopped while idle) acquires it again once — the workspace
 * is still there.
 */
public class RemoteCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoteCommandRunner.class);
    private static final int POLL_SECONDS = 30;

    private final VirtualEnvClient client;
    private final WorkspaceKey key;
    private final ConcurrentMap<String, String> environmentIds;
    private final Duration queueTimeout;
    private final String localAlias;

    /**
     * @param localAlias the session directory on this machine; where a command names it, the
     *                   container gets {@code /workspace} instead. {@code null} when there is none
     */
    public RemoteCommandRunner(VirtualEnvClient client, WorkspaceKey key, ConcurrentMap<String, String> environmentIds,
                               Duration queueTimeout, String localAlias) {
        this.client = client;
        this.key = key;
        this.environmentIds = environmentIds;
        this.queueTimeout = queueTimeout;
        this.localAlias = localAlias == null || localAlias.isBlank() ? null : localAlias;
    }

    @Override
    public String workingDirectory() {
        return RemoteWorkspaceFiles.ROOT.toString();
    }

    @Override
    public Result run(String command, String stdin, Map<String, String> env, Duration timeout) throws CommandException {
        if (localAlias != null) {
            command = command.replace(localAlias, workingDirectory());
        }
        try {
            String id = environmentIds.get(key.id());
            if (id == null) {
                id = running();
            }
            try {
                return result(client.exec(key, id, command, stdin, env, timeout.toSeconds()));
            } catch (VirtualEnvClientException e) {
                if (e.status() != 409 && e.status() != 404) {
                    throw e;
                }
                log.info("Environment {} of {} is no longer running ({}); acquiring it again", id, key.id(),
                        e.getMessage());
                environmentIds.remove(key.id(), id);
                return result(client.exec(key, running(), command, stdin, env, timeout.toSeconds()));
            }
        } catch (IOException e) {
            throw new CommandException(e.getMessage(), e);
        }
    }

    /** Acquires the environment and waits until it runs; its id. */
    private String running() throws IOException, CommandException {
        long deadline = System.nanoTime() + queueTimeout.toNanos();
        VirtualEnvClient.Environment env = client.acquire(key);
        while (!"RUNNING".equals(env.state())) {
            switch (env.state()) {
                case "QUEUED", "STARTING" -> {
                    if (System.nanoTime() > deadline) {
                        throw new CommandException("The environment '" + key.template() + "' is still "
                                + env.state().toLowerCase() + (env.queuePosition() > 0 ? " (position "
                                + env.queuePosition() + " in the queue)" : "") + " after " + queueTimeout.toMinutes()
                                + " minutes. The server is busy; try again later.");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CommandException("Cancelled while waiting for the environment");
                    }
                    if ("QUEUED".equals(env.state())) {
                        log.info("Waiting for environment {} (queue position {})", key.id(), env.queuePosition());
                    }
                    env = client.get(key, env.id(), POLL_SECONDS);
                }
                case "FAILED" -> throw new CommandException("The environment '" + key.template()
                        + "' could not be started: " + env.message());
                default -> env = client.acquire(key);   // STOPPED meanwhile: ask again
            }
        }
        environmentIds.put(key.id(), env.id());
        return env.id();
    }

    private static Result result(VirtualEnvClient.ExecResult r) {
        return new Result(r.exitCode(), r.output() == null ? "" : r.output(), r.truncated(), r.timedOut(), r.durationMs());
    }
}
