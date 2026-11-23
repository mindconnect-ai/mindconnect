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

/** Writes a text file in the session's working directory, replacing one that is there. */
public final class WriteFileTool implements Tool {

    private final HarnessDirectories directories;

    public WriteFileTool(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write_file",
                "Writes a text file in the project. An existing file of that name is replaced.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "The file, relative to the project directory"),
                                "content", Map.of("type", "string", "description", "The whole content of the file")),
                        "required", List.of("path", "content")));
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String path = String.valueOf(call.arguments().get("path"));
        if (!(call.arguments().get("content") instanceof String content)) {
            return ToolOutput.failed("Error: write_file needs the content to write");
        }
        Path file = directories.workspaceFile(session, path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return ToolOutput.ok("Wrote " + path + " (" + content.length() + " characters).");
        } catch (IOException e) {
            return ToolOutput.failed("Error: cannot write " + path + " — " + e.getMessage());
        }
    }
}
