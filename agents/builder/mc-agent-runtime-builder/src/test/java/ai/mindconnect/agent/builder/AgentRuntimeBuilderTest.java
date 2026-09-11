package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.llm.domain.LlmConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The builder must assemble a working runtime without Spring and without a
 * disk footprint: in-memory persistence, seeded config + agent, session
 * opens. No LLM is called — that part needs a live endpoint and stays a demo.
 */
class AgentRuntimeBuilderTest {

    private static AgentDefinition demoAgent() {
        return AgentDefinition.create("test-agent", "test", "You are a test.", null, "test-llm");
    }

    @Test
    void inMemoryRuntimeSeedsAndOpensSessions() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {

            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
            assertThat(runtime.agentDefinitions().findByName("test-agent"))
                    .isPresent();

            AgentSession session = runtime.openSession("test-agent", UserId.of("user-1"));
            assertThat(session.id()).isNotNull();
            assertThat(runtime.sessionService()).isNotNull();
        }
    }

    @Test
    void unknownAgentFailsWithAClearMessage() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().build()) {
            assertThatThrownBy(() -> runtime.openSession("nope", UserId.of("u")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nope");
        }
    }

    @Test
    void attachFileRunsTheDefaultIngestionPath() {
        // On this module's own test classpath the optional modules ARE present
        // (optional only affects consumers), so attach support is live. An
        // empty file exercises the whole path without needing an embedding
        // endpoint: store, template, session store — then "no text content".
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().build()) {
            runtime.agentDefinitions().save(demoAgent());
            AgentSession session = runtime.openSession("test-agent", UserId.of("u"));

            String message = runtime.attachFile(session.id(), "empty.md",
                    new java.io.ByteArrayInputStream(new byte[0]));

            assertThat(message).contains("no text content");
            // The session still learned about the attachment machinery:
            assertThat(runtime.sessionService()).isNotNull();
        }
    }

    @Test
    void aSessionWorksInItsOwnDirectory_andKeepsItWhenMovedToAProject() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {
            AgentSession session = runtime.openSession("test-agent", UserId.of("user-1"));

            assertThat(session.hasWorkingDir()).as("a session opened without a directory gets its own").isTrue();
            java.nio.file.Path own = java.nio.file.Path.of(session.workingDir());
            assertThat(own).endsWith(java.nio.file.Path.of("home", "user-1", "sessions", session.id().value()));
            assertThat(java.nio.file.Files.isDirectory(own)).isTrue();
            assertThat(runtime.sessionService().sessionDir(session.id())).contains(own);

            // The home is also the root a working directory must lie under.
            java.nio.file.Path project = java.nio.file.Files.createDirectories(
                    own.getParent().getParent().resolve("project"));
            AgentSession moved = runtime.changeWorkingDir(session.id(), project);
            assertThat(moved.workingDir()).isEqualTo(project.toRealPath().toString());
            assertThat(moved.additionalDirs()).as("the session's own directory stays reachable")
                    .containsExactly(own.toString());

            java.nio.file.Path elsewhere = java.nio.file.Files.createTempDirectory("elsewhere");
            assertThatThrownBy(() -> runtime.changeWorkingDir(session.id(), elsewhere))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
        }
    }

    @Test
    void aRootThatHoldsNoHomeStillLetsAChatChangeItsList() throws Exception {
        // The admin UI in a container without the server profile: the root is
        // where projects are, the users' home lives elsewhere, under the data
        // directory. The chat's own directory never passed the root and must
        // not have to whenever the list around it changes.
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("root");
        java.nio.file.Path homes = java.nio.file.Files.createTempDirectory("homes");
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .workingDirRoot(root)
                .usersHome(homes.resolve("{user}").toString())
                .build()) {
            var sessions = runtime.sessionService();
            AgentSession session = runtime.openSession("test-agent", UserId.of("user-5"));
            String own = session.workingDir();
            assertThat(java.nio.file.Path.of(own)).startsWith(homes.toRealPath());
            assertThat(java.nio.file.Path.of(own).startsWith(root.toRealPath()))
                    .as("the chat's own directory lies outside the root").isFalse();

            java.nio.file.Path project = java.nio.file.Files.createDirectories(root.resolve("proj"));
            String projectDir = project.toRealPath().toString();
            AgentSession added = runtime.addDirectory(session.id(), project);
            assertThat(added.additionalDirs()).containsExactly(projectDir);

            // The chat's Remove button: the same working directory, the list without the one removed.
            AgentSession removed = sessions.changeWorkingDir(session.id(), added.workingDir(),
                    added.additionalDirs().stream().filter(d -> !d.equals(projectDir)).toList());
            assertThat(removed.workingDir()).isEqualTo(own);
            assertThat(removed.additionalDirs()).isEmpty();

            // In the project, the own directory is on the list — and the list can still change.
            AgentSession moved = runtime.changeWorkingDir(session.id(), project);
            assertThat(moved.additionalDirs()).containsExactly(own);
            java.nio.file.Path other = java.nio.file.Files.createDirectories(root.resolve("other"));
            AgentSession updated = sessions.changeWorkingDir(session.id(), moved.workingDir(),
                    java.util.List.of(own, other.toString()));
            assertThat(updated.workingDir()).isEqualTo(projectDir);
            assertThat(updated.additionalDirs()).containsExactly(own, other.toRealPath().toString());

            // What the session does not have yet is still held to the root.
            java.nio.file.Path elsewhere = java.nio.file.Files.createTempDirectory("elsewhere");
            assertThatThrownBy(() -> sessions.changeWorkingDir(session.id(), projectDir,
                    java.util.List.of(own, elsewhere.toString())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
            assertThatThrownBy(() -> runtime.addDirectory(session.id(), elsewhere))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
            assertThatThrownBy(() -> runtime.changeWorkingDir(session.id(), elsewhere))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
        }
    }

    @Test
    void aChatOpenedByAgentIdAloneGetsItsOwnDirectoryToo() throws Exception {
        // The chat UI and the local client open a chat by agent id alone. That
        // must not be the one way in that forgets the chat's own directory.
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {
            var def = runtime.agentDefinitions().findByName("test-agent").orElseThrow();

            AgentSession session = runtime.sessionService().openChat(def.id(), UserId.of("user-2"));

            assertThat(session.hasWorkingDir()).isTrue();
            java.nio.file.Path own = java.nio.file.Path.of(session.workingDir());
            assertThat(own).endsWith(java.nio.file.Path.of("home", "user-2", "sessions", session.id().value()));
            assertThat(java.nio.file.Files.isDirectory(own)).isTrue();
            assertThat(runtime.sessionService().sessionDir(session.id())).contains(own);
        }
    }

    @Test
    void withoutChoiceEveryChatWorksInItsOwnDirectory_andNoOtherIsTaken() throws Exception {
        // A shared server: nobody points a chat at the server's file system.
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .workingDirChoice(false)
                .build()) {
            AgentSession session = runtime.openSession("test-agent", UserId.of("user-3"));
            assertThat(session.hasWorkingDir()).as("the chat's own directory is not a choice").isTrue();

            java.nio.file.Path project = java.nio.file.Files.createDirectories(
                    java.nio.file.Path.of(session.workingDir()).getParent().getParent().resolve("project"));
            assertThatThrownBy(() -> runtime.changeWorkingDir(session.id(), project))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mindconnect.working-dirs.choice");
            assertThatThrownBy(() -> runtime.addDirectory(session.id(), project))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.sessionService().changeWorkingDir(session.id(), null, null))
                    .as("clearing it would take the chat out of its own directory")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.openSession("test-agent", UserId.of("user-3"), project))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.sessionService().workingDirChoice()).isFalse();
        }
    }

    @Test
    void deletingAChatRemovesItsOwnDirectory_butNothingTheUserChose() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {
            AgentSession own = runtime.openSession("test-agent", UserId.of("user-4"));
            java.nio.file.Path ownDir = java.nio.file.Path.of(own.workingDir());
            java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(ownDir.resolve("uploads"))
                    .resolve("notes.md"), "mine");

            java.nio.file.Path project = java.nio.file.Files.createDirectories(
                    ownDir.getParent().getParent().resolve("keep-me"));
            java.nio.file.Files.writeString(project.resolve("AGENTS.md"), "stays");
            java.nio.file.Files.createSymbolicLink(ownDir.resolve("link-to-project"), project);
            AgentSession chosen = runtime.openSession("test-agent", UserId.of("user-4"), project);

            runtime.sessionService().deleteSession(own.id());
            runtime.sessionService().deleteSession(chosen.id());

            assertThat(ownDir).doesNotExist();
            assertThat(project.resolve("AGENTS.md"))
                    .as("a link inside the chat's directory is removed, not followed; a chosen directory is never touched")
                    .exists();
        }
    }

    @Test
    void switchingTheModelKeepsTheSessionsDirectories() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .llmConfig(LlmConfig.lmStudio("other-llm", "other-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {
            AgentSession session = runtime.openSession("test-agent", UserId.of("user-1"));
            java.nio.file.Path own = java.nio.file.Path.of(session.workingDir());
            java.nio.file.Path project = java.nio.file.Files.createDirectories(own.getParent().getParent().resolve("proj"));
            runtime.changeWorkingDir(session.id(), project);

            var def = runtime.agentDefinitions().findByName("test-agent").orElseThrow();
            AgentSession switched = runtime.sessionService().replaceSessionAgent(session.id(),
                    new ai.mindconnect.agent.runtime.domain.session.SessionAgentRef(
                            def.id(), true, def.name(), "other-llm", null, null, null));

            assertThat(switched.workingDir()).as("a model switch is not a cd").isEqualTo(project.toRealPath().toString());
            assertThat(switched.additionalDirs()).containsExactly(own.toString());
        }
    }
}
