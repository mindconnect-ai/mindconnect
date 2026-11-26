package ai.mindconnect.llm.adapter.price;

import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.gateway.port.out.LlmPriceRepositoryContract;

class InMemoryLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    @Override
    protected LlmPriceRepository repository() {
        return new InMemoryLlmPriceRepository(LlmScopeSupplier.local());
    }
}
