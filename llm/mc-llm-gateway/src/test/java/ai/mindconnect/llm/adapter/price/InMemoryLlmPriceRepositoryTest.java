package ai.mindconnect.llm.adapter.price;

import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.gateway.port.out.LlmPriceRepositoryContract;

class InMemoryLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    @Override
    protected LlmPriceRepository repository() {
        return new InMemoryLlmPriceRepository(TenantScope.local());
    }
}
