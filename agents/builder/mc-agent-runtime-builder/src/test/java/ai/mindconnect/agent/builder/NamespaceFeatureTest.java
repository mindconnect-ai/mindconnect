package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.namespace.NamespaceFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.service.task.ScopeTaskAdvisor;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.taskqueue.TaskAdvisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With the namespace feature the runtime works in the namespace of the
 * current call: what one namespace stores, another does not see, and the
 * queue carries the scope with every task.
 */
class NamespaceFeatureTest {

    private static final Namespace A = new Namespace("a");
    private static final Namespace B = new Namespace("b");

    @Test
    void repositoriesFollowTheBoundNamespace() throws Exception {
        var feature = new NamespaceFeature().fallback(Namespace.DEFAULT);
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory())
                .install(feature)
                .install(new ToolsFeature());
        builder.feature(CoreFeature.class).llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"));
        try (AgentRuntime runtime = builder.build()) {
            ThreadBoundScope scope = feature.scope();
            assertThat(runtime.beans().get(ScopeSupplier.class)).isSameAs(scope);
            assertThat(runtime.beans().all(TaskAdvisor.class)).anyMatch(a -> a instanceof ScopeTaskAdvisor);

            // an agent and a session in namespace a …
            AgentSession inA = scope.runIn(Scope.of(A), () -> {
                runtime.agentDefinitions().save(AgentDefinition.create("agent-a", "a", "You are a.", null, "test-llm"));
                return runtime.openSession("agent-a", UserId.of("u"));
            });
            // … are not there in namespace b, which has its own stores
            scope.runIn(Scope.of(B), () -> {
                assertThat(runtime.agentDefinitions().findByName("agent-a")).isEmpty();
                assertThatThrownBy(() -> runtime.sessionService().findSession(inA.id())).isInstanceOf(Exception.class);
                return null;
            });
            // … and are there again in a
            assertThat(scope.runIn(Scope.of(A), () -> runtime.sessionService().findSession(inA.id()).id())).isEqualTo(inA.id());
            // the LLM config was seeded into the fallback namespace, not into a
            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
            assertThat(scope.runIn(Scope.of(A), () -> runtime.llmConfigs().findByName("test-llm"))).isEmpty();
        }
    }

    @Test
    void strictRefusesAnUnboundCall() throws Exception {
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory())
                .install(new NamespaceFeature().strict());
        try (AgentRuntime runtime = builder.build()) {
            assertThatThrownBy(() -> runtime.agentDefinitions().findByName("x")).isInstanceOf(Exception.class);
        }
    }
}
