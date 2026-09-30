package ai.mindconnect.llm.adapter.price;

import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepositoryContract;

class InMemoryLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    @Override
    protected LlmPriceRepository repository() {
        return new InMemoryLlmPriceRepository();
    }
}
