package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileLlmConfigRepositoryTest {

    @TempDir
    Path dir;

    /** The builder's mapper, which fails on unknown properties — the strictest a host hands the store. */
    private final ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void a_config_written_with_a_field_this_version_does_not_know_still_loads() throws IOException {
        FileLlmConfigRepository repo = new FileLlmConfigRepository(dir, strict, ScopeSupplier.fixed(new Namespace("test")));
        LlmConfig stored = LlmConfig.lmStudio("local", "qwen", "http://localhost:1234");
        repo.save(stored);
        Path file = fileOf(stored);
        Files.writeString(file, Files.readString(file).replaceFirst("\\{", "{\"fieldFromTheFuture\":true,"));

        assertThat(repo.findById(stored.id())).map(LlmConfig::model).contains("qwen");
    }

    private Path fileOf(LlmConfig config) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().equals(config.id().value() + ".json"))
                    .findFirst().orElseThrow();
        }
    }
}
