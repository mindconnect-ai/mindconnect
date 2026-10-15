package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** The LLM repositories as files under {@code <baseDir>/<namespace>}, the namespace the scope names per call. */
public class FileLlmRepositoryFactory implements LlmRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ScopeSupplier scope;

    public FileLlmRepositoryFactory(Path baseDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.scope = scope;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new FileLlmConfigRepository(baseDir, objectMapper, scope);
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new FileLlmPriceRepository(baseDir, objectMapper, scope);
    }
}
