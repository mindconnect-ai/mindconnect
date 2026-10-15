package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;

/** The LLM repositories in memory, one store per namespace — nothing survives the process. */
public class InMemoryLlmRepositoryFactory implements LlmRepositoryFactory {

    private final ScopeSupplier scope;

    public InMemoryLlmRepositoryFactory(ScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new InMemoryLlmConfigRepository(scope);
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new InMemoryLlmPriceRepository(scope);
    }
}
