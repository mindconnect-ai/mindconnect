package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;

import java.nio.file.Path;

/** The LLM repositories as files under {@code <baseDir>/<namespace>}. */
public class FileLlmRepositoryFactory implements LlmRepositoryFactory {

    private final Path baseDir;
    private final Namespace namespace;

    public FileLlmRepositoryFactory(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir;
        this.namespace = namespace;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new FileLlmConfigRepository(baseDir, namespace);
    }
}
