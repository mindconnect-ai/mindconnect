package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.FeatureException;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.fileupload.FileUploadFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.TaskQueue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime as a core plus installed features: the smallest runtime chats
 * without tools, features are reached by class, a configured feature replaces
 * a default, a missing dependency is refused at install time, and a feature
 * of one's own registers beans and advisors like the shipped ones.
 */
class AgentRuntimeFeaturesTest {

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("mc-features");
    }

    private static AgentDefinition demoAgent() {
        return AgentDefinition.create("test-agent", "test", "You are a test.", null, "test-llm");
    }

    @Test
    void theSmallestRuntimeIsTheCoreFeature() throws Exception {
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir()));
        // of() installed the core; it is reached to configure, not installed again
        builder.feature(CoreFeature.class)
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"))
                .agentDefinition(demoAgent());
        try (AgentRuntime runtime = builder.build()) {

            assertThat(runtime.features().all()).extracting(RuntimeFeature::name)
                    .containsExactly("core");
            assertThat(runtime.features().has(ToolsFeature.class)).isFalse();
            // no tools feature: the core offers the agents an empty registry, and a session still opens
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).isEmpty();
            AgentSession session = runtime.openSession("test-agent", UserId.of("u"));
            assertThat(session.id()).isNotNull();
            assertThat(runtime.beans().get(LlmChat.class)).isNotNull();
            assertThat(runtime.beans().get(TaskQueue.class)).isNotNull();   // the in-process default
            assertThat(runtime.feature(CoreFeature.class)).isSameAs(builder.feature(CoreFeature.class));
        }
    }

    @Test
    void theCoreFeatureCanBeReplacedByAnInstanceOfItsClass() throws Exception {
        CoreFeature mine = new CoreFeature();
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir())).install(mine).build()) {
            assertThat(runtime.feature(CoreFeature.class)).isSameAs(mine);
            assertThat(runtime.features().all()).extracting(RuntimeFeature::name).containsExactly("core");
        }
    }

    @Test
    void aSettingCannotChangeOnceTheRuntimeIsBuilt() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().build()) {
            CoreFeature core = runtime.feature(CoreFeature.class);
            assertThat(core.configured()).isTrue();
            assertThatThrownBy(() -> core.encryptionKey("0123456789abcdef"))
                    .isInstanceOf(FeatureException.class)
                    .hasMessageContaining("CoreFeature")
                    .hasMessageContaining("cannot change");
            // data still changes — through the beans
            runtime.llmConfigs().save(LlmConfig.lmStudio("late", "m", "http://localhost:9"));
            assertThat(runtime.llmConfigs().findByName("late")).isPresent();
        }
    }

    @Test
    void featuresAreReachedByClassAndTheirBeansByType() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"))
                .build()) {

            assertThat(runtime.feature(CoreFeature.class).defaultLlmConfigName()).isEqualTo("test-llm");
            assertThat(runtime.beans().get(LlmConfigRepository.class).findByName("test-llm")).isPresent();
            assertThat(runtime.llmConfigs()).isSameAs(runtime.beans().get(LlmConfigRepository.class));
            assertThat(runtime.features().all()).extracting(RuntimeFeature::name)
                    .containsExactly("core", "skills", "tools", "workflows", "file-upload");
        }
    }

    @Test
    void aConfiguredFeatureReplacesTheDefaultOfItsName() {
        CoreFeature mine = new CoreFeature().encryptionKey("0123456789abcdef")
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"));
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().install(mine).build()) {
            assertThat(runtime.feature(CoreFeature.class)).isSameAs(mine);
            assertThat(runtime.features().all()).filteredOn(f -> f.name().equals("core")).hasSize(1);
            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
        }
    }

    @Test
    void configureReachesAnInstalledFeatureInPlace() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"))
                .configure(ToolsFeature.class, tools -> tools.disabled("bash"))
                .build()) {
            assertThat(runtime.beans().get(ToolRegistry.class).knownToolNames()).doesNotContain("bash");
        }
    }

    @Test
    void aMissingDependencyIsRefusedWhenInstalling() throws Exception {
        var builder = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir()));
        assertThatThrownBy(() -> builder.install(new FileUploadFeature()))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("FileUploadFeature")
                .hasMessageContaining("needs ToolsFeature");
    }

    @Test
    void aFeatureNotInstalledIsAnErrorNotNull() throws Exception {
        var builder = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir()));
        assertThatThrownBy(() -> builder.configure(ToolsFeature.class, tools -> tools.disabled("bash")))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("ToolsFeature")
                .hasMessageContaining("not installed");
        assertThatThrownBy(() -> builder.feature(ToolsFeature.class)).isInstanceOf(FeatureException.class);
    }

    /** A feature of one's own: an advisor for the tools and one for the queue, and a bean of its own. */
    static class AuditFeature implements RuntimeFeature {
        record Audit(String who) { }
        final ToolAdvisor toolAdvisor = (inv, chain) -> chain.proceed(inv);
        final TaskAdvisor taskAdvisor = new TaskAdvisor() { };

        @Override public String name() { return "audit"; }
        @Override public Set<Class<? extends RuntimeFeature>> dependsOn() { return Set.of(ToolsFeature.class); }
        @Override public void configure(FeatureContext ctx) {
            ctx.instance(Audit.class, new Audit("me"));
            ctx.contribute(ToolAdvisor.class, toolAdvisor);
            ctx.contribute(TaskAdvisor.class, taskAdvisor);
            // reads another feature's bean through the runtime, lazily
            ctx.bean(String.class, () -> "audit of " + ctx.runtime().beans().get(LlmConfigRepository.class).getClass().getSimpleName());
        }
    }

    @Test
    void anOwnFeatureContributesBeansAndAdvisors() {
        var audit = new AuditFeature();
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().install(audit).build()) {
            assertThat(runtime.feature(AuditFeature.class)).isSameAs(audit);
            assertThat(runtime.beans().get(AuditFeature.Audit.class).who()).isEqualTo("me");
            assertThat(runtime.beans().all(ToolAdvisor.class)).containsExactly(audit.toolAdvisor);
            assertThat(runtime.beans().all(TaskAdvisor.class)).containsExactly(audit.taskAdvisor);
            assertThat(runtime.beans().get(String.class)).startsWith("audit of ");
        }
    }

    @Test
    void installFromClasspathFindsTheShippedFeaturesInDependencyOrder() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir()))
                .installFromClasspath()
                .build()) {
            var names = runtime.features().all().stream().map(RuntimeFeature::name).toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "core", "skills", "tools", "workflows", "file-upload");
            assertThat(names.indexOf("tools")).isLessThan(names.indexOf("file-upload"));
        }
    }

    @Test
    void installFromClasspathKeepsAnInstanceInstalledUnderTheSameName() throws Exception {
        CoreFeature mine = new CoreFeature().llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"));
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory(tempDir()))
                .install(mine)
                .installFromClasspath()
                .build()) {
            assertThat(runtime.feature(CoreFeature.class)).isSameAs(mine);
            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
        }
    }

    @Test
    void theBuilderIsFrozenAfterBuild() {
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.useInMemoryPersistence();
        try (AgentRuntime runtime = builder.build()) {
            assertThatThrownBy(() -> builder.install(new AuditFeature())).isInstanceOf(FeatureException.class);
            assertThatThrownBy(() -> builder.property("x", "y")).isInstanceOf(FeatureException.class);
            assertThat(runtime.beans().has(LlmChat.class)).isTrue();
        }
    }
}
