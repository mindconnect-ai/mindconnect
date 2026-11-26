package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Reads a text file of the session's working directory. */
public final class ReadFileTool implements Tool {

    private final HarnessDirectories directories;

    public ReadFileTool(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file", "Reads a text file of the project and returns its content.",
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string",
                                "description", "The file, relative to the project directory")),
                        "required", List.of("path")));
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String path = String.valueOf(call.arguments().get("path"));
        Path file = directories.workspaceFile(session, path);
        if (!Files.isRegularFile(file)) {
            return ToolOutput.failed("Error: there is no file " + path);
        }
        try {
            return ToolOutput.ok(Files.readString(file));
        } catch (IOException e) {
            return ToolOutput.failed("Error: cannot read " + path + " — " + e.getMessage());
        }
    }
}
