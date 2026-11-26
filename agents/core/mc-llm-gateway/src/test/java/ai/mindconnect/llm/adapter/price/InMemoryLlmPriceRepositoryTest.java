package ai.mindconnect.llm.adapter.price;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepositoryContract;

class InMemoryLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    @Override
    protected LlmPriceRepository repository() {
        return new InMemoryLlmPriceRepository(ScopeSupplier.local());
    }
}
