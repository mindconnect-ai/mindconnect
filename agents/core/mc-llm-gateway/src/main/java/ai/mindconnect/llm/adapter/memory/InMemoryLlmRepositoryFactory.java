package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;

/** The LLM repositories in memory — nothing survives the process. */
public class InMemoryLlmRepositoryFactory implements LlmRepositoryFactory {

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new InMemoryLlmConfigRepository();
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new InMemoryLlmPriceRepository();
    }
}
