package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** The LLM repositories as files under {@code <baseDir>/<namespace>}. */
public class FileLlmRepositoryFactory implements LlmRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final Namespace namespace;

    public FileLlmRepositoryFactory(Path baseDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new FileLlmConfigRepository(baseDir, objectMapper, namespace);
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new FileLlmPriceRepository(baseDir, objectMapper, namespace);
    }
}
