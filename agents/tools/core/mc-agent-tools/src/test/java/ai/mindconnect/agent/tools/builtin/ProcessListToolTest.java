package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.ToolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * process_list: shows this session's background processes and nothing else,
 * ends none of them, and is found next to process_kill.
 */
class ProcessListToolTest {

    @TempDir
    Path tmp;

    @Test
    void listsOnlyThisSessionsProcesses_andEndsNone() throws Exception {
        SessionId session = SessionId.random();
        SessionId other = SessionId.random();
        ProcessListTool list = new ProcessListTool(session);
        assertThat(list.execute(Map.of())).isEqualTo("No background processes in this session.");

        Process mine = new ProcessBuilder("sleep", "60").start();
        Process theirs = new ProcessBuilder("sleep", "61").start();
        try {
            BackgroundProcesses.register(session, mine, "sleep 60", tmp.resolve("mine.log"));
            BackgroundProcesses.register(other, theirs, "sleep 61", tmp.resolve("theirs.log"));

            String out = list.execute(Map.of());
            assertThat(out).isEqualTo("pid " + mine.pid() + " (running): sleep 60 — log " + tmp.resolve("mine.log"))
                    .doesNotContain("sleep 61");
            assertThat(list.execute(Map.of("pid", mine.pid())))
                    .as("a pid is ignored: listing never kills").isEqualTo(out);
            assertThat(mine.isAlive()).isTrue();
            assertThat(new ProcessKillTool(session).execute(Map.of()))
                    .as("process_kill without a pid still lists, for agents that call it so").isEqualTo(out);
        } finally {
            BackgroundProcesses.kill(session, mine.pid());
            BackgroundProcesses.kill(other, theirs.pid());
        }
        mine.waitFor(5, TimeUnit.SECONDS);
        assertThat(list.execute(Map.of())).isEqualTo("No background processes in this session.");
    }

    @Test
    void anExitedProcessIsListedAsExited() throws Exception {
        SessionId session = SessionId.random();
        Process done = new ProcessBuilder("true").start();
        done.waitFor(5, TimeUnit.SECONDS);
        BackgroundProcesses.register(session, done, "true", tmp.resolve("done.log"));
        try {
            assertThat(new ProcessListTool(session).execute(Map.of()))
                    .isEqualTo("pid " + done.pid() + " (exited): true — log " + tmp.resolve("done.log"));
        } finally {
            BackgroundProcesses.kill(session, done.pid());
        }
    }

    @Test
    void takesNoParameters_andPointsAwayFromPs() {
        ProcessListTool list = new ProcessListTool(null);

        assertThat(list.name()).isEqualTo("process_list");
        assertThat((Map<?, ?>) list.parametersSchema().get("properties")).isEmpty();
        assertThat(list.description()).contains("background=true").contains("not ps");
        assertThat(new ProcessKillTool(null).description()).contains("call process_list instead")
                .doesNotContain("Without a pid");
    }

    @Test
    void isRegisteredNextToProcessKill() {
        var factories = ServiceLoader.load(ToolFactory.class).stream().map(ServiceLoader.Provider::get).toList();

        assertThat(factories).filteredOn(f -> f.name().equals("process_list")).singleElement()
                .satisfies(f -> {
                    assertThat(f.group()).isEqualTo("files");
                    assertThat(f.create(null, null)).isInstanceOf(ProcessListTool.class);
                });
    }
}
