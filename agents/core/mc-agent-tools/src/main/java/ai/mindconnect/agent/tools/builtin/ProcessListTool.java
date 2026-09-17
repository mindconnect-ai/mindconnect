package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists the processes {@code bash} started in the background for this
 * session — pid, whether it still runs, the command and its log. Read-only.
 *
 * <p>A tool of its own because a listing hidden in {@code process_kill}
 * was not found: asked for the background processes, a model ran
 * {@code ps}, or called the kill tool with a pid and ended the server.
 */
public class ProcessListTool implements Tool {

    private final SessionId sessionId;

    public ProcessListTool(SessionId sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "process_list";
    }

    @Override
    public String description() {
        return "Lists the processes bash started with background=true in this session — a dev server, "
                + "a watcher — with pid, whether each is still running, its command and its log file. "
                + "Changes nothing. Use this, not ps, to see what this session has running.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", new String[]{}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return listing(sessionId);
    }

    /** The session's background processes, one per line, or a sentence saying there are none. */
    static String listing(SessionId sessionId) {
        List<BackgroundProcesses.Entry> running = BackgroundProcesses.list(sessionId);
        return running.isEmpty() ? "No background processes in this session."
                : running.stream().map(BackgroundProcesses.Entry::describe).collect(Collectors.joining("\n"));
    }
}
