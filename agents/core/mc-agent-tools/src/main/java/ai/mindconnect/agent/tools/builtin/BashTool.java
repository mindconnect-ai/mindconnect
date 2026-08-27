package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final int TIMEOUT_SECONDS = 60;

    private final File workingDir;

    public BashTool(File workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Executes a bash command in the working directory " + workingDir.getAbsolutePath() +
               " and returns the combined stdout and stderr output.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "The bash command to execute"
                        )
                ),
                "required", new String[]{"command"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }
        log.info("bash> {}", command);
        Process process = null;
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines()
                        .peek(line -> log.info("bash| {}", line))
                        .collect(Collectors.joining("\n"));
            }
            if (!finished) {
                process.destroyForcibly();
                log.warn("bash: timed out after {}s", TIMEOUT_SECONDS);
                return "Error: command timed out after " + TIMEOUT_SECONDS + " seconds\n" + output;
            }
            int exitCode = process.exitValue();
            log.info("bash exit={}", exitCode);
            if (exitCode != 0) {
                return "Exit code " + exitCode + ":\n" + output;
            }
            return output.isEmpty() ? "(no output)" : output;
        } catch (InterruptedException ie) {
            // Cancel from the turn thread: re-set interrupt flag and bail out.
            Thread.currentThread().interrupt();
            log.info("bash: interrupted — terminating subprocess");
            return "Error: bash command cancelled";
        } catch (Exception e) {
            log.error("bash: error executing command: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } finally {
            // Guarantee no zombie subprocess survives the tool call, regardless
            // of how we exited (interrupt, exception, normal return).
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                log.warn("bash: subprocess force-destroyed in finally");
            }
        }
    }
}
