package ai.mindconnect.conversation.port.out;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;

/** The conversations of the namespace the current call works in, their participants with them. */
public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(ConversationId id);

    /** Newest first. */
    List<Conversation> findAll(PageRequest page);

    void deleteById(ConversationId id);
}
