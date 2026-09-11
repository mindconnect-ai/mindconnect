package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The prompt names the working directory — and says nothing when there is none. */
class SystemPromptRendererWorkingDirTest {

    private static AgentSession session() {
        return AgentSession.start(AgentId.random(), UserId.of("u"), ConversationId.random());
    }

    @Test
    void aSessionWithAWorkingDirectoryGetsTheSection() {
        String section = SystemPromptRenderer.workingDirSection(session().withWorkingDir("/home/me/src/app"));

        assertThat(section)
                .startsWith("\n\n## Working directory\n")
                .contains("`/home/me/src/app`")
                .contains("`bash` runs in it");
    }

    @Test
    void additionalDirectoriesAreListed() {
        String section = SystemPromptRenderer.workingDirSection(
                session().withWorkingDir("/work").withAdditionalDirs(java.util.List.of("/lib", "/data")));

        assertThat(section)
                .contains("You are working in `/work`")
                .contains("You may also use these directories, by absolute path:")
                .contains("\n- `/lib`")
                .contains("\n- `/data`");

        String alone = SystemPromptRenderer.workingDirSection(
                session().withAdditionalDirs(java.util.List.of("/lib")));
        assertThat(alone).contains("You may use these directories, by absolute path:").doesNotContain("working in");
    }

    @Test
    void noWorkingDirectoryNoSection() {
        assertThat(SystemPromptRenderer.workingDirSection(session())).isEmpty();
        assertThat(SystemPromptRenderer.workingDirSection(null)).isEmpty();
    }
}
