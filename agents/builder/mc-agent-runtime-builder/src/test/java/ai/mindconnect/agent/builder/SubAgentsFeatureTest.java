package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.runtime.feature.FeatureException;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.subagents.SubAgentsFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Delegation is a feature: without it the core does not delegate, with it up to the configured depth. */
class SubAgentsFeatureTest {

    @Test
    void theCoreAloneDoesNotDelegate() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory()).build()) {
            SubAgentSupport support = runtime.beans().get(SubAgentSupport.class);
            assertThat(support.enabled()).isFalse();
        }
    }

    @Test
    void theFeatureSwitchesDelegationOnWithItsDepth() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory())
                .install(new ToolsFeature())
                .install(new SubAgentsFeature().maxDepth(2))
                .build()) {
            SubAgentSupport support = runtime.beans().get(SubAgentSupport.class);
            assertThat(support.enabled()).isTrue();
            assertThat(support.maxDepth()).isEqualTo(2);
        }
    }

    @Test
    void delegationIsAToolCallAndNeedsTheToolsFeature() throws Exception {
        assertThatThrownBy(() -> AgentRuntimeBuilder.of(Persistence.inMemory()).install(new SubAgentsFeature()))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("needs ToolsFeature");
    }
}
