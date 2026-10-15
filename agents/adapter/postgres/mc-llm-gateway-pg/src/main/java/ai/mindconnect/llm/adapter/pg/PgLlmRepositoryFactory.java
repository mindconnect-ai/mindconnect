package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;

/** The LLM repositories as Postgres tables in the namespace the scope names per call; each is created with its schema. */
public class PgLlmRepositoryFactory implements LlmRepositoryFactory {

    private final Sql sql;
    private final ScopeSupplier scope;

    public PgLlmRepositoryFactory(Sql sql, ScopeSupplier scope) {
        this.sql = sql;
        this.scope = scope;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new PgLlmConfigRepository(sql, scope).initSchema();
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new PgLlmPriceRepository(sql, scope).initSchema();
    }
}
