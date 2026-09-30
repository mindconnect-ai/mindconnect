package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;

/** The LLM repositories as Postgres tables, bound to one namespace; each is created with its schema. */
public class PgLlmRepositoryFactory implements LlmRepositoryFactory {

    private final Sql sql;
    private final Namespace namespace;

    public PgLlmRepositoryFactory(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = namespace;
    }

    @Override
    public LlmConfigRepository llmConfigRepository() {
        return new PgLlmConfigRepository(sql, namespace).initSchema();
    }

    @Override
    public LlmPriceRepository llmPriceRepository() {
        return new PgLlmPriceRepository(sql, namespace).initSchema();
    }
}
