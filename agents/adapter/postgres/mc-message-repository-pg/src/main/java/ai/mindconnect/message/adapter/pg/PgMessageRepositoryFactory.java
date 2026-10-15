package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;

/** Conversations and messages as Postgres tables in the namespace the scope names per call; each is created with its schema. */
public class PgMessageRepositoryFactory implements MessageRepositoryFactory {

    private final Sql sql;
    private final ScopeSupplier scope;

    public PgMessageRepositoryFactory(Sql sql, ScopeSupplier scope) {
        this.sql = sql;
        this.scope = scope;
    }

    @Override
    public ConversationRepository conversationRepository() {
        return new PgConversationRepository(sql, scope).initSchema();
    }

    @Override
    public MessageRepository messageRepository() {
        return new PgMessageRepository(sql, scope).initSchema();
    }
}
