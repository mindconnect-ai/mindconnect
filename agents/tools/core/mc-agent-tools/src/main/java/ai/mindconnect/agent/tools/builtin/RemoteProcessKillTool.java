package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.workspace.CommandRunner;

import java.time.Duration;
import java.util.Map;

/**
 * {@code process_kill} for {@link RemoteBashTool}: the background processes it
 * recorded in the container, ended as a whole process group.
 */
public class RemoteProcessKillTool implements Tool {

    private final CommandRunner runner;

    public RemoteProcessKillTool(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "process_kill";
    }

    @Override
    public String description() {
        return new ProcessKillTool(null).description();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return new ProcessKillTool(null).parametersSchema();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("pid");
        String script;
        if (raw == null || raw.toString().isBlank()) {
            // Kept for agents and models that still call it this way; process_list is the tool for it.
            return RemoteProcessListTool.listing(runner);
        } else {
            long pid;
            try {
                pid = raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException e) {
                return "Error: pid must be a number, got " + raw;
            }
            script = "if ! grep -q '^" + pid + " ' " + RemoteBashTool.PROCESS_FILE + " 2>/dev/null; then "
                    + "echo 'Error: pid " + pid + " is not a background process of this session.'; exit 0; fi; "
                    + "kill -TERM -- -" + pid + " 2>/dev/null || kill -TERM " + pid + " 2>/dev/null; sleep 1; "
                    + "if kill -0 " + pid + " 2>/dev/null; then kill -KILL -- -" + pid + " 2>/dev/null; fi; "
                    + "echo 'Ended pid " + pid + " and its process group.'";
        }
        try {
            String output = runner.run(script, null, Map.of(), Duration.ofSeconds(20)).output().stripTrailing();
            return output.isEmpty() ? "(no output)" : output;
        } catch (CommandRunner.CommandException e) {
            return "Error: " + e.getMessage();
        }
    }
}
