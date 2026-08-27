package ai.mindconnect.message.port.out;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(UUID id);

    List<Conversation> findByNamespace(Namespace namespace, PageRequest page);
}
