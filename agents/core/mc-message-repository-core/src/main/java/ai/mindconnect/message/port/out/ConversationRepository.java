package ai.mindconnect.message.port.out;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationId;

import java.util.List;
import java.util.Optional;

/** Storage of conversations, per tenant. */
public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(ConversationId id);

    /** A page of one tenant's conversations. */
    List<Conversation> findAll(PageRequest page);
}
