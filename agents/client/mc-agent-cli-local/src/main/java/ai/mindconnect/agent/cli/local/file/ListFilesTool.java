package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Lists the files of the session's working directory, the harness' own folder left out. */
public final class ListFilesTool implements Tool {

    private static final int MOST_FILES = 200;

    private final HarnessDirectories directories;

    public ListFilesTool(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("list_files", "Lists the files of the project, with their paths.",
                Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        Path workspace = directories.workspaceFile(session, "");
        try (Stream<Path> files = Files.walk(workspace)) {
            String listing = files.filter(Files::isRegularFile)
                    .filter(file -> !HarnessDirectories.isConfigFile(workspace, file))
                    .map(file -> workspace.relativize(file).toString())
                    .sorted()
                    .limit(MOST_FILES)
                    .collect(Collectors.joining("\n"));
            return ToolOutput.ok(listing.isEmpty() ? "The project has no files." : listing);
        } catch (IOException e) {
            return ToolOutput.failed("Error: cannot list the project — " + e.getMessage());
        }
    }
}
