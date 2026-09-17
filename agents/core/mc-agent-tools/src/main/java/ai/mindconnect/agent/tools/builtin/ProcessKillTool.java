package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ends a process {@code bash} started in the background — the whole tree,
 * so a dev server's port is free again. Only this session's processes:
 * one started elsewhere is not the model's to kill. Listing is
 * {@code process_list}; without a pid this tool still lists too, so
 * existing agents and habits keep working.
 */
public class ProcessKillTool implements Tool {

    private final SessionId sessionId;

    public ProcessKillTool(SessionId sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "process_kill";
    }

    @Override
    public String description() {
        return "Ends a process that bash started with background=true, with everything it spawned — "
                + "a dev server, a watcher. `pid` is the process to end, as bash or process_list reported "
                + "it. To see what is running, call process_list instead.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pid", Map.of("type", "integer",
                                "description", "The pid of the background process to end, as bash or process_list reported it.")
                ),
                "required", new String[]{}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("pid");
        List<BackgroundProcesses.Entry> running = BackgroundProcesses.list(sessionId);
        if (raw == null || raw.toString().isBlank()) {
            // Kept for agents and models that still call it this way; process_list is the tool for it.
            return ProcessListTool.listing(sessionId);
        }
        long pid;
        try {
            pid = raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return "Error: pid must be a number, got " + raw;
        }
        var entry = BackgroundProcesses.find(sessionId, pid);
        if (entry.isEmpty()) {
            return "Error: pid " + pid + " is not a background process of this session."
                    + (running.isEmpty() ? " There are none." : " This session has:\n"
                    + running.stream().map(BackgroundProcesses.Entry::describe).collect(Collectors.joining("\n")));
        }
        boolean wasAlive = entry.get().alive();
        BackgroundProcesses.kill(sessionId, pid);
        return (wasAlive ? "Killed pid " + pid : "Pid " + pid + " had already exited; removed") + ": "
                + entry.get().command() + "\nLog: " + entry.get().log();
    }
}
