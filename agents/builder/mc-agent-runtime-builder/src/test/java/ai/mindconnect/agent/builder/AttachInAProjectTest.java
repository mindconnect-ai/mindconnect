package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.llm.domain.LlmConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An attached file is copied into the session's own directory and the prompt
 * names that path — so a session working in a project must still reach it
 * with the file tools, which only go where the session's directories are.
 */
class AttachInAProjectTest {

    private static AgentRuntime runtime() {
        return AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(AgentDefinition.create("test-agent", "test", "You are a test.", null, "test-llm"))
                .build();
    }

    @Test
    void aSessionWorkingInAProjectReachesItsUploads() throws Exception {
        try (AgentRuntime runtime = runtime()) {
            UserId user = UserId.of("attach-user-1");
            Path project = Files.createDirectories(runtime.sessionService().userHome()
                    .homeOf(user).orElseThrow().resolve("project"));
            AgentSession session = runtime.openSession("test-agent", user, project);

            // An empty file needs no embedding endpoint: copied, then "no text content".
            runtime.attachFile(session.id(), "notes.md", new ByteArrayInputStream(new byte[0]));

            AgentSession after = runtime.sessionService().findSession(session.id());
            Path own = runtime.sessionService().sessionDir(session.id()).orElseThrow();
            assertThat(after.workingDir()).as("still in the project").isEqualTo(project.toRealPath().toString());
            assertThat(after.attachedFile("notes.md")).get().extracting(AttachedFile::path)
                    .isEqualTo(own.resolve("uploads").resolve("notes.md").toString());
            assertThat(after.additionalDirs()).as("the uploads' directory is reachable")
                    .containsExactly(own.toString());
        }
    }

    @Test
    void aSessionInItsOwnDirectoryGetsNothingAdded() {
        try (AgentRuntime runtime = runtime()) {
            AgentSession session = runtime.openSession("test-agent", UserId.of("attach-user-2"));

            runtime.attachFile(session.id(), "notes.md", new ByteArrayInputStream(new byte[0]));

            AgentSession after = runtime.sessionService().findSession(session.id());
            assertThat(after.attachedFile("notes.md")).get().extracting(AttachedFile::path).isNotNull();
            assertThat(after.additionalDirs()).isEmpty();
        }
    }
}
