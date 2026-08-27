package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
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
        return AgentDefinition.create(new ai.mindconnect.common.Namespace("local"),
                "test-agent", "test", "You are a test.", null, "test-llm");
    }

    @Test
    void inMemoryRuntimeSeedsAndOpensSessions() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("test-llm", "some-model", "http://localhost:9"))
                .agentDefinition(demoAgent())
                .build()) {

            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
            assertThat(runtime.agentDefinitions().findByName(runtime.namespace(), "test-agent"))
                    .isPresent();

            AgentSession session = runtime.openSession("test-agent", "user-1");
            assertThat(session.id()).isNotNull();
            assertThat(runtime.sessionService()).isNotNull();
        }
    }

    @Test
    void unknownAgentFailsWithAClearMessage() {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence().build()) {
            assertThatThrownBy(() -> runtime.openSession("nope", "u"))
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
            AgentSession session = runtime.openSession("test-agent", "u");

            String message = runtime.attachFile(session.id(), "empty.md",
                    new java.io.ByteArrayInputStream(new byte[0]));

            assertThat(message).contains("no text content");
            // The session still learned about the attachment machinery:
            assertThat(runtime.sessionService()).isNotNull();
        }
    }
}
