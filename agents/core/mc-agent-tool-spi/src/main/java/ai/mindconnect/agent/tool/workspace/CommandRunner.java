package ai.mindconnect.agent.tool.workspace;

import java.time.Duration;
import java.util.Map;

/**
 * Runs shell commands where a workspace lives. For a remote workspace that is
 * a container of its own, so {@code bash} and {@code code_execute} need no
 * guard against touching the agent's machine.
 */
public interface CommandRunner {

    /** Where commands run and relative paths point, e.g. {@code /workspace}. */
    String workingDirectory();

    /**
     * Runs {@code command} with {@code bash -c}, standard error merged into the
     * output, killed after {@code timeout}. Waits for the environment to become
     * available first, when it has to be started or queued.
     *
     * @param stdin fed to the command, or {@code null}
     * @param env   extra environment variables for this command
     */
    Result run(String command, String stdin, Map<String, String> env, Duration timeout)
            throws CommandException;

    /**
     * @param output    standard output and standard error, interleaved, capped
     * @param truncated whether output beyond the cap was dropped
     * @param timedOut  whether the command was killed at its timeout
     */
    record Result(int exitCode, String output, boolean truncated, boolean timedOut, long durationMs) {
    }

    /** The command could not be run at all: no environment, queue wait too long, server unreachable. */
    class CommandException extends Exception {
        public CommandException(String message) {
            super(message);
        }

        public CommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
