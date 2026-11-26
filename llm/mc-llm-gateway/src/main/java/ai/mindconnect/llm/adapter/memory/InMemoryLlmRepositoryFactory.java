package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.management.api.port.out.LlmRepositoryFactory;

/** The LLM repositories in memory, one store per namespace — nothing survives the process. */
public class InMemoryLlmRepositoryFactory implements LlmRepositoryFactory {

    private final LlmScopeSupplier scope;

    public InMemoryLlmRepositoryFactory(LlmScopeSupplier scope) {
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
