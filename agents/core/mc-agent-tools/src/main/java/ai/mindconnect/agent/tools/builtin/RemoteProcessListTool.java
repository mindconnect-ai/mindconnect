package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.workspace.CommandRunner;

import java.time.Duration;
import java.util.Map;

/**
 * {@code process_list} for {@link RemoteBashTool}: the background processes it
 * recorded in the container that are still running. Read-only.
 */
public class RemoteProcessListTool implements Tool {

    /** Prints each recorded background process that is still alive, or that there are none. */
    static final String LISTING_SCRIPT = "mkdir -p .mc && touch " + RemoteBashTool.PROCESS_FILE + "; found=0; "
            + "while read -r pid log; do if kill -0 \"$pid\" 2>/dev/null; then "
            + "cmd=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null); "
            + "echo \"pid $pid (running): $cmd — log $log\"; found=1; fi; done < " + RemoteBashTool.PROCESS_FILE
            + "; [ $found = 1 ] || echo 'No background processes in this session.'";

    private final CommandRunner runner;

    public RemoteProcessListTool(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "process_list";
    }

    @Override
    public String description() {
        return new ProcessListTool(null).description();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return new ProcessListTool(null).parametersSchema();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return listing(runner);
    }

    static String listing(CommandRunner runner) {
        try {
            String output = runner.run(LISTING_SCRIPT, null, Map.of(), Duration.ofSeconds(20)).output().stripTrailing();
            return output.isEmpty() ? "(no output)" : output;
        } catch (CommandRunner.CommandException e) {
            return "Error: " + e.getMessage();
        }
    }
}
