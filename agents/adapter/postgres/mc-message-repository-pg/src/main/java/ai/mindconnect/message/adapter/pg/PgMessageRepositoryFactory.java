package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;

/** Conversations and messages as Postgres tables, bound to one namespace; each is created with its schema. */
public class PgMessageRepositoryFactory implements MessageRepositoryFactory {

    private final Sql sql;
    private final Namespace namespace;

    public PgMessageRepositoryFactory(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = namespace;
    }

    @Override
    public ConversationRepository conversationRepository() {
        return new PgConversationRepository(sql, namespace).initSchema();
    }

    @Override
    public MessageRepository messageRepository() {
        return new PgMessageRepository(sql, namespace).initSchema();
    }
}
