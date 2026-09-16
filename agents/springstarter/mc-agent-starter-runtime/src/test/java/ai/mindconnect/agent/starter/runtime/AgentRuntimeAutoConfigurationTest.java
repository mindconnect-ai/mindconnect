package ai.mindconnect.agent.starter.runtime;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.tool.ToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The runtime starter builds the runtime whatever the persistence. On anything
 * but files the tools feature registers no {@link ToolRepository}, the runtime
 * asks the host's beans for one — and the starter's own export of that type
 * must not be the answer: it needs the runtime being built, and the application
 * did not start on Postgres.
 */
class AgentRuntimeAutoConfigurationTest {

    @TempDir
    Path tmp;

    private ApplicationContextRunner runner(Persistence persistence) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(Persistence.class, () -> persistence)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("mindconnect.encryption.secret-key=0123456789abcdef");
    }

    @Test
    void aRuntimeWithoutAToolRepositoryOfItsOwnStarts() {
        runner(Persistence.inMemory(tmp)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context.getBean(AgentRuntime.class).beans().find(ToolRepository.class)).isEmpty();
        });
    }

    @Test
    void onFilesTheRuntimesToolRepositoryIsExported() {
        runner(Persistence.file(tmp)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolRepository.class))
                    .isSameAs(context.getBean(AgentRuntime.class).beans().get(ToolRepository.class));
        });
    }

    @Test
    void aHostsOwnBeanIsStillFoundThroughTheFallback() {
        ToolRepository hosts = mock(ToolRepository.class);
        runner(Persistence.inMemory(tmp))
                .withBean("hostToolRepository", ToolRepository.class, () -> hosts)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AgentRuntimeAutoConfiguration.hostBean(context, ToolRepository.class)).containsSame(hosts);
                });
    }
}
