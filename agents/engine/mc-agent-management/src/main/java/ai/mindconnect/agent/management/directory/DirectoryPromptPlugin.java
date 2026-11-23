package ai.mindconnect.agent.management.directory;

import java.util.List;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Tells the model where its session works and what holds there: the working
 * directory, the user's standing instructions and those of the project in
 * that directory. Read anew for every model call, so a changed directory or
 * an edited file shows in the next one.
 */
public final class DirectoryPromptPlugin implements Plugin {

    private final InstructionFileReader instructions;

    public DirectoryPromptPlugin(InstructionFileReader instructions) {
        this.instructions = instructions;
    }

    @Override
    public String name() {
        return "directory-prompt";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.after(RuntimeHook.INSTRUCTIONS, (context, text) -> {
            Session session = context.session();
            String working = session.metadata().get(ManagedSession.WORKING_DIRECTORY) instanceof String dir
                    && !dir.isBlank() ? dir : null;
            // The user's before the project's: the more specific one has the last word.
            return text + workingDirectory(working, additional(session))
                    + instructions.userSection(session.userId() == null ? null : session.userId().value())
                    + instructions.projectSection(working);
        });
    }

    private static List<String> additional(Session session) {
        return session.metadata().get(ManagedSession.ADDITIONAL_DIRECTORIES) instanceof List<?> dirs
                ? dirs.stream().map(String::valueOf).toList() : List.of();
    }

    /** So the model knows what {@code .} means and where {@code bash} runs without probing for it. */
    private static String workingDirectory(String working, List<String> additional) {
        if (working == null && additional.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\n## Working directory\n");
        if (working != null) {
            text.append("You are working in `").append(working).append("`. Relative paths in the "
                    + "file tools resolve against it, `bash` runs in it, and `.` means this directory.");
        }
        if (!additional.isEmpty()) {
            text.append(working != null ? " You may also use these directories, by absolute path:"
                    : "You may use these directories, by absolute path:");
            additional.forEach(dir -> text.append("\n- `").append(dir).append('`'));
        }
        return text.toString();
    }
}
