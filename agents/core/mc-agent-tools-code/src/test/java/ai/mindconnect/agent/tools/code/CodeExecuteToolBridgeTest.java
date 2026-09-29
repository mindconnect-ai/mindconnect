package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.ToolCallScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code code_execute} with a bridge: the program in the container calls the
 * agent's tools and the host answers, over a stub container CLI that plays
 * both the runtime and a program — so the whole path is exercised without a
 * container runtime.
 */
class CodeExecuteToolBridgeTest {

    @TempDir
    Path dir;

    private CodeExecutionService service;
    private final List<String> invoked = new ArrayList<>();

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void theProgramCallsATool_andOnlyWhatItPrintsComesBack() throws IOException {
        CodeExecuteTool tool = tool(bridge(), programThatCallsATool());

        String result = tool.execute(Map.of(
                "language", "python",
                "tools", List.of("calendar_list"),
                "code", "print('whatever')"));

        // The program got the tool's real answer, in the container.
        assertThat(result).contains("answer-for-calendar_list");
        assertThat(invoked).containsExactly("calendar_list");
        // And the model is told what ran, without the results running through it.
        assertThat(result).contains("--- tools called ---").contains("calendar_list");
    }

    @Test
    void theProgramIsToldWhereItsExchangeIs() throws IOException {
        CodeExecuteTool tool = tool(bridge(), programThatCallsATool());

        tool.execute(Map.of("language", "python", "tools", List.of("calendar_list"), "code", "x"));

        String exec = calls().stream().filter(c -> c.startsWith("exec ")).findFirst().orElseThrow();
        assertThat(exec).contains("-e " + ToolExchange.DIR_ENV + "=" + workingDir().resolve(ToolExchange.DIR_NAME));
    }

    @Test
    void theExchangeIsGoneWhenTheProgramIs() throws IOException {
        CodeExecuteTool tool = tool(bridge(), programThatCallsATool());

        tool.execute(Map.of("language", "python", "tools", List.of("calendar_list"), "code", "x"));

        assertThat(workingDir().resolve(ToolExchange.DIR_NAME).toFile().list()).isEmpty();
    }

    @Test
    void aToolTheSessionDoesNotHaveIsRefusedBeforeTheContainerStarts() throws IOException {
        CodeExecuteTool tool = tool(bridge(), plainProgram());

        String result = tool.execute(Map.of(
                "language", "python", "tools", List.of("send_missiles"), "code", "x"));

        assertThat(result).startsWith("Error: not a tool of this session: send_missiles");
        assertThat(calls()).noneMatch(c -> c.startsWith("run "));
    }

    @Test
    void withoutABridgeTheToolsArgumentIsRefusedWithAReason() throws IOException {
        CodeExecuteTool tool = tool(null, plainProgram());

        String result = tool.execute(Map.of(
                "language", "python", "tools", List.of("calendar_list"), "code", "x"));

        assertThat(result).startsWith("Error: this installation cannot call tools from code");
    }

    @Test
    void aProgramThatDeclaresNothingRunsWithoutAnExchange() throws IOException {
        CodeExecuteTool tool = tool(bridge(), plainProgram());

        String result = tool.execute(Map.of("language", "python", "code", "print(1)"));

        assertThat(result).contains("ran[print(1)]").doesNotContain("--- tools called ---");
        String exec = calls().stream().filter(c -> c.startsWith("exec ")).findFirst().orElseThrow();
        assertThat(exec).doesNotContain(ToolExchange.DIR_ENV);
        assertThat(workingDir().resolve(ToolExchange.DIR_NAME)).doesNotExist();
    }

    @Test
    void withABridgeTheModelIsOfferedTheArgumentAndTheTools() throws IOException {
        CodeExecuteTool tool = tool(bridge(), plainProgram());

        assertThat(tool.parametersSchema().toString()).contains("tools");
        assertThat(tool.description())
                .contains("mc_tools").contains("calendar_list").contains("gmail_send");
    }

    @Test
    void withoutABridgeNothingIsSaidAboutCallingTools() throws IOException {
        CodeExecuteTool tool = tool(null, plainProgram());

        assertThat(tool.parametersSchema().toString()).doesNotContain("tools");
        assertThat(tool.description()).doesNotContain("mc_tools");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private Path workingDir() throws IOException {
        return Files.createDirectories(dir.resolve("work")).toAbsolutePath();
    }

    private CodeExecuteTool tool(CodeExecuteTool.ToolBridge bridge, Path stubBinary) throws IOException {
        ContainerCli cli = ContainerCli.detect(stubBinary.toString()).orElseThrow();
        service = new CodeExecutionService(cli, new CodeExecutionService.Settings(
                "256m", "1", Duration.ofSeconds(20), Duration.ofMinutes(10), dir.resolve("scratch")));
        return new CodeExecuteTool(service, CodeLanguages.defaults(), "session-a", "none", null,
                new SessionDirs(workingDir(), List.of()), bridge);
    }

    private CodeExecuteTool.ToolBridge bridge() {
        ScopedToolInvoker invoker = new ScopedToolInvoker() {
            @Override
            public Result invoke(ToolCallScope scope, String callerToolName, String toolName,
                                 Map<String, Object> arguments) {
                synchronized (invoked) {
                    invoked.add(toolName);
                }
                return new Result("answer-for-" + toolName, false, 5L);
            }

            @Override
            public List<String> callableTools(ToolCallScope scope, String callerToolName) {
                return List.of("calendar_list", "gmail_send");
            }
        };
        ToolCallScope scope = new ToolCallScope(UserId.of("alice"), SessionId.random(), null);
        return new CodeExecuteTool.ToolBridge(invoker, scope, "session-a",
                Duration.ofSeconds(30), Duration.ofMillis(20));
    }

    /** A stub CLI that only echoes the program back, as the existing suite's does. */
    private Path plainProgram() throws IOException {
        return stub("""
                  exec)
                    INPUT=$(cat)
                    echo "ran[$INPUT]"
                    exit 0 ;;
                """);
    }

    /**
     * A stub CLI whose {@code exec} behaves like a program using mc_tools:
     * it finds its exchange in the arguments, writes a request with its
     * marker, waits for the answer and prints it.
     */
    private Path programThatCallsATool() throws IOException {
        return stub("""
                  exec)
                    TOOLS_DIR=""
                    for a in "$@"; do
                      case "$a" in MC_TOOLS_DIR=*) TOOLS_DIR="${a#MC_TOOLS_DIR=}" ;; esac
                    done
                    cat > /dev/null
                    if [ -z "$TOOLS_DIR" ]; then echo "no exchange"; exit 0; fi
                    printf '{"tool":"calendar_list","arguments":{}}' > "$TOOLS_DIR/0001.request.json"
                    : > "$TOOLS_DIR/0001.request.json.ready"
                    i=0
                    while [ ! -f "$TOOLS_DIR/0001.response.json.ready" ] && [ $i -lt 200 ]; do
                      sleep 0.05; i=$((i+1))
                    done
                    cat "$TOOLS_DIR/0001.response.json"
                    exit 0 ;;
                """);
    }

    private Path stub(String execCase) throws IOException {
        Path script = dir.resolve("stubcli");
        String body = """
                #!/bin/sh
                DIR="$(dirname "$0")"
                echo "$*" >> "$DIR/calls.log"
                case "$1" in
                  --version) echo "stub 1.0"; exit 0 ;;
                  run)
                    N=$(cat "$DIR/run-count" 2>/dev/null || echo 0); N=$((N+1)); echo "$N" > "$DIR/run-count"
                    echo "container-$N"; exit 0 ;;
                %EXEC%
                  ps) printf ""; exit 0 ;;
                  rm) exit 0 ;;
                esac
                echo "unexpected: $*" >&2; exit 1
                """.replace("%EXEC%", execCase.stripTrailing());
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private List<String> calls() throws IOException {
        Path log = dir.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }
}
