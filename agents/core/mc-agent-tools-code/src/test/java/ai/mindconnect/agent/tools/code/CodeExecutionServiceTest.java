package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the container session lifecycle against a stub shell script that
 * mimics the docker/podman CLI — no container runtime needed, and the calls
 * the service makes are asserted verbatim from the stub's log.
 */
class CodeExecutionServiceTest {

    @TempDir
    Path dir;

    private CodeExecutionService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    /**
     * Writes a stub container CLI. It logs every invocation to calls.log,
     * answers --version, hands out container-N ids for run, echoes stdin back
     * for exec (after {@code execDelaySeconds}), and lists {@code psOutput}
     * for ps.
     */
    private Path stub(int execDelaySeconds, String psOutput) throws IOException {
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
                  exec)
                    INPUT=$(cat)
                    sleep %DELAY%
                    echo "ran[$INPUT]"
                    exit 0 ;;
                  ps) printf "%PS%"; exit 0 ;;
                  rm) exit 0 ;;
                esac
                echo "unexpected: $*" >&2; exit 1
                """
                .replace("%DELAY%", String.valueOf(execDelaySeconds))
                .replace("%PS%", psOutput);
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private List<String> calls() throws IOException {
        Path log = dir.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private CodeExecutionService newService(Path stubBinary, Duration execTimeout) {
        ContainerCli cli = ContainerCli.detect(stubBinary.toString()).orElseThrow();
        service = new CodeExecutionService(cli, new CodeExecutionService.Settings(
                "256m", "1", execTimeout, Duration.ofMinutes(10), dir.resolve("scratch")));
        return service;
    }

    private static CodeLanguages.CodeLanguage python() {
        return CodeLanguages.defaults().get("python");
    }

    @Test
    void firstCallStartsContainerFurtherCallsReuseIt() throws IOException {
        var svc = newService(stub(0, ""), Duration.ofSeconds(10));

        var first = svc.execute("session-a", python(), "none", null, "print(1)");
        var second = svc.execute("session-a", python(), "none", null, "print(2)");

        assertThat(first.stdout()).contains("ran[print(1)]");
        assertThat(second.stdout()).contains("ran[print(2)]");
        List<String> runs = calls().stream().filter(c -> c.startsWith("run ")).toList();
        List<String> execs = calls().stream().filter(c -> c.startsWith("exec ")).toList();
        assertThat(runs).hasSize(1);
        assertThat(execs).hasSize(2);
        // Both execs hit the same container with the language's stdin command.
        assertThat(execs).allSatisfy(c -> assertThat(c).contains("container-1").contains("python3 -"));
    }

    @Test
    void containerIsHardenedAndSessionScratchDirMounted() throws IOException {
        var svc = newService(stub(0, ""), Duration.ofSeconds(10));

        svc.execute("session-b", python(), "none", null, "print(1)");

        String run = calls().stream().filter(c -> c.startsWith("run ")).findFirst().orElseThrow();
        assertThat(run).contains("--network none")
                .contains("--memory 256m")
                .contains("--cpus 1")
                .contains("--label " + CodeExecutionService.LABEL + "=1")
                .contains(":/workspace")
                .contains("python:3.12-slim");
        assertThat(dir.resolve("scratch/session-b")).isDirectory();
    }

    @Test
    void differentSessionsGetDifferentContainers() throws IOException {
        var svc = newService(stub(0, ""), Duration.ofSeconds(10));

        svc.execute("session-a", python(), "none", null, "print(1)");
        svc.execute("session-c", python(), "none", null, "print(1)");

        assertThat(calls().stream().filter(c -> c.startsWith("run ")).toList()).hasSize(2);
    }

    @Test
    void timedOutExecutionKillsTheSessionContainer() throws IOException {
        var svc = newService(stub(3, ""), Duration.ofMillis(400));

        var result = svc.execute("session-d", python(), "none", null, "while True: pass");

        assertThat(result.timedOut()).isTrue();
        assertThat(calls()).anySatisfy(c -> assertThat(c).startsWith("rm -f container-1"));

        // The next call must start over with a fresh container.
        svc.execute("session-d", python(), "none", null, "print(1)");
        assertThat(calls().stream().filter(c -> c.startsWith("run ")).toList()).hasSize(2);
    }

    @Test
    void staleLabeledContainersAreRemovedAtStartup() throws IOException {
        newService(stub(0, "old-1\\nold-2\\n"), Duration.ofSeconds(10));

        assertThat(calls()).anySatisfy(c -> assertThat(c).startsWith("ps -aq --filter label=" + CodeExecutionService.LABEL));
        assertThat(calls()).anySatisfy(c -> assertThat(c).isEqualTo("rm -f old-1"));
        assertThat(calls()).anySatisfy(c -> assertThat(c).isEqualTo("rm -f old-2"));
    }

    @Test
    void detectPrefersConfiguredBinaryAndFailsClosed() throws IOException {
        Path stub = stub(0, "");

        assertThat(ContainerCli.detect(stub.toString())).isPresent();
        assertThat(ContainerCli.detect(dir.resolve("missing-binary").toString())).isEmpty();
    }

    @Test
    void toolFormatsResultAndRejectsUnknownLanguage() throws IOException {
        var svc = newService(stub(0, ""), Duration.ofSeconds(10));
        Tool tool = new CodeExecuteTool(svc, CodeLanguages.defaults(), "session-e", "none");

        assertThat(tool.execute(Map.of("language", "python", "code", "print(1)")))
                .startsWith("exit code: 0")
                .contains("--- stdout ---")
                .contains("ran[print(1)]");
        assertThat(tool.execute(Map.of("language", "cobol", "code", "x")))
                .startsWith("Error: unknown language");
        assertThat(tool.execute(Map.of("language", "python", "code", "")))
                .startsWith("Error: 'code'");
    }

    @Test
    void networkModeIsPartOfContainerCreationAndSessionKey() throws IOException {
        var svc = newService(stub(0, ""), Duration.ofSeconds(10));

        svc.execute("session-n", python(), "none", null, "print(1)");
        svc.execute("session-n", python(), "bridge", null, "print(1)");

        List<String> runs = calls().stream().filter(c -> c.startsWith("run ")).toList();
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).contains("--network none");
        assertThat(runs.get(1)).contains("--network bridge");
    }

    @Test
    void agentOverrideSwitchesNetworkAndInvalidValuesFallBack() throws IOException {
        Path stub = stub(0, "");
        var factory = new CodeExecuteToolFactory();
        factory.bind(env(Map.of(
                "codeExecRuntime", stub.toString(),
                "dataBaseDir", dir.resolve("data").toString())));
        service = null; // factory owns its service; nothing extra to close here

        var agentTool = new ai.mindconnect.agent.tool.AgentTool(
                null, null, "code_execute", null, Map.of("network", "bridge"), true);
        Tool bridged = factory.create(agentTool, new ToolCallScope(null, "u", UUID.randomUUID(), null));
        assertThat(bridged.description()).contains("Network access is enabled");
        bridged.execute(Map.of("language", "python", "code", "print(1)"));
        assertThat(calls()).anySatisfy(c -> assertThat(c).startsWith("run ").contains("--network bridge"));

        var invalid = new ai.mindconnect.agent.tool.AgentTool(
                null, null, "code_execute", null, Map.of("network", "host"), true);
        Tool fallback = factory.create(invalid, new ToolCallScope(null, "u", UUID.randomUUID(), null));
        assertThat(fallback.description()).contains("NO network access");
    }

    @Test
    void factoryDeclaresTheNetworkOverride() throws IOException {
        Path stub = stub(0, "");
        var factory = new CodeExecuteToolFactory();
        factory.bind(env(Map.of(
                "codeExecRuntime", stub.toString(),
                "dataBaseDir", dir.resolve("data").toString())));
        service = null;

        Map<String, Object> schema = factory.overridesSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) properties.get("network");
        assertThat(network.get("enum")).isEqualTo(List.of("none", "bridge"));
        assertThat(network.get("default")).isEqualTo("none");
    }

    @Test
    void factoryIsUnavailableWithoutContainerRuntimeAndBindsWithOne() throws IOException {
        Path stub = stub(0, "");

        var noRuntime = new CodeExecuteToolFactory();
        noRuntime.bind(env(Map.of("codeExecRuntime", dir.resolve("missing").toString())));
        assertThat(noRuntime.isAvailable()).isFalse();

        var withRuntime = new CodeExecuteToolFactory();
        withRuntime.bind(env(Map.of(
                "codeExecRuntime", stub.toString(),
                "dataBaseDir", dir.resolve("data").toString())));
        assertThat(withRuntime.isAvailable()).isTrue();

        UUID sessionId = UUID.randomUUID();
        Tool tool = withRuntime.create(null, new ToolCallScope(null, "user", sessionId, null));
        assertThat(tool.execute(Map.of("language", "node", "code", "console.log(1)")))
                .contains("ran[console.log(1)]");
        assertThat(dir.resolve("data/code-exec").resolve(sessionId.toString())).isDirectory();
    }

    private static ToolEnvironment env(Map<String, String> strings) {
        return new ToolEnvironment() {
            @Override public <T> Optional<T> get(Class<T> type) { return Optional.empty(); }
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(strings.get(key)).filter(s -> !s.isBlank());
            }
        };
    }
}
