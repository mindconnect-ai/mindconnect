package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * Finds the lines of the project's text files that hold one of the query's
 * words. No index and no ranking: a project a harness works in is small
 * enough to read, and a model asks again with other words when it must.
 */
public final class SearchFilesTool implements Tool {

    private static final int MOST_LINES = 40;
    private static final int SHORTEST_WORD = 4;

    private final HarnessDirectories directories;

    public SearchFilesTool(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("search_files",
                "Searches the project's text files and returns the matching lines with file and line number. "
                        + "A line matches when it holds one of the query's words.",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string",
                                "description", "Keywords, for example a name or a term")),
                        "required", List.of("query")));
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String query = String.valueOf(call.arguments().get("query"));
        List<String> words = Arrays.stream(query.toLowerCase().split("[^\\p{L}\\p{N}-]+"))
                .filter(word -> word.length() >= SHORTEST_WORD).toList();
        if (words.isEmpty()) {
            return ToolOutput.failed("Error: the query needs a word of at least " + SHORTEST_WORD + " letters");
        }
        Path workspace = directories.workspaceFile(session, "");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(workspace)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(candidate -> !HarnessDirectories.isConfigFile(workspace, candidate)).sorted().toList()) {
                collectHits(workspace, file, words, hits);
            }
        } catch (IOException e) {
            return ToolOutput.failed("Error: cannot search the project — " + e.getMessage());
        }
        return ToolOutput.ok(hits.isEmpty()
                ? "No line matches \"" + query + "\"."
                : String.join("\n", hits.subList(0, Math.min(hits.size(), MOST_LINES))));
    }

    private static void collectHits(Path workspace, Path file, List<String> words, List<String> hits)
            throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException e) {
            return;     // not a text file: nothing in it a model could read
        }
        for (int number = 0; number < lines.size(); number++) {
            String line = lines.get(number).toLowerCase();
            if (words.stream().anyMatch(line::contains)) {
                hits.add(workspace.relativize(file) + ":" + (number + 1) + ": " + lines.get(number).strip());
            }
        }
    }
}
