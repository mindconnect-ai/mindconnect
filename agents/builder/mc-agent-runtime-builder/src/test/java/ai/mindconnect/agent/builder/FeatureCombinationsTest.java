package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.fileupload.FileUploadFeature;
import ai.mindconnect.agent.runtime.feature.skills.SkillsFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.feature.transcription.TranscriptionFeature;
import ai.mindconnect.agent.runtime.feature.workflows.WorkflowsFeature;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.llm.domain.LlmConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every tier of the runtime, one at a time: what an installed feature switches
 * on, and what its absence leaves as a null object or a clear refusal. On this
 * module's own test classpath the optional capability modules are present, so
 * a feature that is installed is also live.
 */
class FeatureCombinationsTest {

    private static AgentRuntimeBuilder core() throws Exception {
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory(Files.createTempDirectory("mc-combo")));
        builder.feature(CoreFeature.class)
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"))
                .agentDefinition(AgentDefinition.create("test-agent", "test", "You are a test.", null, "test-llm"));
        return builder;
    }

    private static Skill greeting() {
        return Skill.fromMarkdown("greeting", "---\ndescription: says hi\n---\nSay hi.", SkillSource.MANAGED, null);
    }

    private static java.util.List<String> names(AgentRuntime runtime) {
        return runtime.features().all().stream().map(RuntimeFeature::name).toList();
    }

    @Test
    void coreAlone_noToolsNoSkillsNoFiles() throws Exception {
        try (AgentRuntime runtime = core().build()) {
            assertThat(names(runtime)).containsExactly("core");
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).isEmpty();
            assertThat(runtime.beans().has(SkillRepository.class)).isFalse();
            assertThat(runtime.beans().get(SkillCatalog.class).all(UserId.of("u"), null)).isEmpty();
            assertThat(runtime.beans().find(FileStore.class)).isEmpty();
            AgentSession session = runtime.openSession("test-agent", UserId.of("u"));
            assertThatThrownBy(() -> runtime.attachFile(session.id(), "a.md", new ByteArrayInputStream(new byte[0])))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("file-upload feature");
        }
    }

    @Test
    void coreAndTools_toolsAreLiveSkillsAreNot() throws Exception {
        try (AgentRuntime runtime = core().install(new ToolsFeature()).build()) {
            assertThat(names(runtime)).containsExactly("core", "tools");
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).isNotEmpty();
            assertThat(runtime.features().has(SkillsFeature.class)).isFalse();
            assertThat(runtime.beans().get(SkillCatalog.class).all(UserId.of("u"), null)).isEmpty();
            assertThat(runtime.beans().find(FileStore.class)).isEmpty();
        }
    }

    @Test
    void coreAndSkills_skillsAreStoredWithoutAnyTool() throws Exception {
        try (AgentRuntime runtime = core().install(new SkillsFeature().skill(greeting())).build()) {
            assertThat(names(runtime)).containsExactly("core", "skills");
            assertThat(runtime.beans().get(SkillRepository.class).findByName("greeting")).isPresent();
            assertThat(runtime.beans().get(SkillCatalog.class).all(UserId.of("u"), null))
                    .extracting(Skill::name).contains("greeting");
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).isEmpty();
        }
    }

    @Test
    void coreToolsAndFileUpload_attachWorksWithoutWorkflows() throws Exception {
        try (AgentRuntime runtime = core().install(new ToolsFeature()).install(new FileUploadFeature()).build()) {
            assertThat(names(runtime)).containsExactly("core", "tools", "file-upload");
            assertThat(runtime.beans().find(FileStore.class)).isPresent();
            assertThat(runtime.features().has(WorkflowsFeature.class)).isFalse();
            AgentSession session = runtime.openSession("test-agent", UserId.of("u"));
            String message = runtime.attachFile(session.id(), "empty.md", new ByteArrayInputStream(new byte[0]));
            assertThat(message).contains("no text content");   // the whole path ran, up to the empty file
        }
    }

    @Test
    void coreAndWorkflows_seedsIntoTheFileStoreWithoutTools() throws Exception {
        Path dataDir = Files.createTempDirectory("mc-combo-wf");
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.file(dataDir))
                .install(new WorkflowsFeature().seed("workflows/hello.json"));
        builder.feature(CoreFeature.class).llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"));
        try (AgentRuntime runtime = builder.build()) {
            assertThat(names(runtime)).containsExactly("core", "workflows");
            assertThat(dataDir.resolve("local/workflows/hello.json")).exists();
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).isEmpty();
        }
    }

    @Test
    void everything_isWhatTheFactoriesInstall() throws Exception {
        try (AgentRuntime explicit = core()
                     .install(new SkillsFeature()).install(new ToolsFeature())
                     .install(new WorkflowsFeature()).install(new FileUploadFeature())
                     .install(new TranscriptionFeature())
                     .build();
             AgentRuntime factory = AgentRuntimeBuilder.useInMemoryPersistence().build()) {
            assertThat(names(explicit)).isEqualTo(names(factory));
            assertThat(explicit.beans().get(ToolRegistry.class).knownToolNames())
                    .isEqualTo(factory.beans().get(ToolRegistry.class).knownToolNames());
        }
    }
}
