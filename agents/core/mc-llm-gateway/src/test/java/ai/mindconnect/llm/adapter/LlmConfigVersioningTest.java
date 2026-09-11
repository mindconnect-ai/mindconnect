package ai.mindconnect.llm.adapter;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An LLM config saved from a form opened before another save is refused — file and in-memory alike. */
class LlmConfigVersioningTest {

    @Test
    void theFileStoreChecksVersions(@TempDir Path dir) {
        versionsAreChecked(new FileLlmConfigRepository(dir, new Namespace("test")));
    }

    @Test
    void theInMemoryStoreChecksVersions() {
        versionsAreChecked(new InMemoryLlmConfigRepository());
    }

    private static void versionsAreChecked(LlmConfigRepository repo) {
        LlmConfig config = LlmConfig.lmStudio("local", "qwen", "http://localhost:1234");
        repo.save(config);
        LlmConfig stored = repo.findById(config.id()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1L);

        repo.save(stored.withContextWindowTokens(8192));
        assertThat(repo.findById(config.id())).map(LlmConfig::version).contains(2L);

        assertThatThrownBy(() -> repo.save(stored.withContextWindowTokens(4096)))
                .isInstanceOf(StaleVersionException.class);
        assertThat(repo.findById(config.id())).map(LlmConfig::contextWindowTokens).contains(8192);

        repo.save(stored.withVersion(null).withContextWindowTokens(2048));
        assertThat(repo.findById(config.id()).orElseThrow())
                .extracting(LlmConfig::version, LlmConfig::contextWindowTokens)
                .containsExactly(3L, 2048);
    }
}
