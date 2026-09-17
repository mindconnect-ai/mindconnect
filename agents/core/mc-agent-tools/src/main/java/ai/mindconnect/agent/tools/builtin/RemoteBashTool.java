package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.workspace.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code bash} in a workspace that lives elsewhere — a virtual environment's
 * container. Same name, same parameters and the same result shape as
 * {@link BashTool}, so an agent needs no second set of instructions.
 *
 * <p>The container is the boundary, so the guards {@link BashTool} needs on the
 * agent's own machine (refusing {@code &}, {@code nohup}, tracking the process
 * tree) are not needed here. A background command is started in a session of
 * its own inside the container, logged under {@code .mc/logs}, and recorded in
 * {@code .mc/processes} for {@code process_kill}.
 */
public class RemoteBashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoteBashTool.class);

    static final String PROCESS_FILE = ".mc/processes";

    private final CommandRunner runner;

    public RemoteBashTool(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Runs a bash command in this session's own Linux container, in the working directory "
                + runner.workingDirectory() + ", and returns its combined stdout and stderr (at most "
                + BashTool.MAX_OUTPUT_CHARS + " characters — pipe long output through head, tail or grep). "
                + "Files in " + runner.workingDirectory() + " are the ones the file tools read and write, and "
                + "they stay for this session; anything installed elsewhere may be gone after a pause. Each "
                + "call is a fresh shell: cd and variables do not carry over. Default timeout "
                + BashTool.DEFAULT_TIMEOUT_SECONDS + "s, raise `timeout` (up to " + BashTool.MAX_TIMEOUT_SECONDS
                + ") for a build or test run. A command that does not return on its own — a dev server, a "
                + "watcher — must run with `background`: the call returns with the pid and a log file; end "
                + "it with process_kill. The first call of a session may wait while the container starts.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string", "description", "The bash command to execute. A server "
                + "or watcher that keeps running needs background=true."));
        props.put("timeout", Map.of("type", "integer",
                "description", "Seconds the command may run before it is killed. Default "
                        + BashTool.DEFAULT_TIMEOUT_SECONDS + ", max " + BashTool.MAX_TIMEOUT_SECONDS
                        + ". Ignored with background."));
        props.put("background", Map.of("type", "boolean",
                "description", "Start the command and return at once with its pid and log file, for a "
                        + "server or watcher that never exits. Default false."));
        return Map.of("type", "object", "properties", props, "required", new String[]{"command"});
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }
        boolean background = Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("background", "false")));
        int timeout = BashTool.timeoutSeconds(arguments.get("timeout"));
        log.info("bash (remote){}> {}", background ? " background" : "", command);
        try {
            if (background) {
                CommandRunner.Result started = runner.run(backgroundScript(command), null, Map.of(),
                        Duration.ofSeconds(30));
                return started.output().isBlank() ? "(no output)" : started.output().stripTrailing();
            }
            CommandRunner.Result result = runner.run(command, null, Map.of(), Duration.ofSeconds(timeout));
            String text = cap(result.output(), result.truncated());
            if (result.timedOut()) {
                return "Error: command timed out after " + timeout + " seconds"
                        + (text.isEmpty() ? "" : "\n" + text);
            }
            if (result.exitCode() != 0) {
                return "Exit code " + result.exitCode() + ":\n" + text;
            }
            return text.isEmpty() ? "(no output)" : text;
        } catch (CommandRunner.CommandException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Starts {@code command} in a new session (so {@code process_kill} can end
     * the whole group), records it, waits briefly and quotes the log.
     */
    static String backgroundScript(String command) {
        return "mkdir -p .mc/logs && log=.mc/logs/bash-$(date +%s%N).log && "
                + "setsid nohup bash -c " + quote(command) + " > \"$log\" 2>&1 < /dev/null & pid=$!; "
                + "echo \"$pid $log\" >> " + PROCESS_FILE + "; "
                + "sleep 3; "
                + "if kill -0 $pid 2>/dev/null; then state=running; else state=exited; fi; "
                + "echo \"Started in background: pid $pid ($state), log $PWD/$log\"; "
                + "echo '--- log so far ---'; tail -n " + BashTool.BACKGROUND_LOG_LINES + " \"$log\"";
    }

    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static String cap(String output, boolean truncated) {
        String text = output == null ? "" : output.stripTrailing();
        if (text.length() > BashTool.MAX_OUTPUT_CHARS) {
            text = text.substring(0, BashTool.MAX_OUTPUT_CHARS);
            truncated = true;
        }
        return truncated ? text + "\n[output truncated at " + BashTool.MAX_OUTPUT_CHARS + " characters]" : text;
    }
}
