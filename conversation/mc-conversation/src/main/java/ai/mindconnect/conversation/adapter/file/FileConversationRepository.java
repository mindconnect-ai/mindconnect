package ai.mindconnect.conversation.adapter.file;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;

/**
 * Conversations under {@code {base}/{namespace}/conversation-threads/{id}/conversation.json},
 * their messages beside them. Not under {@code conversations/}: there the
 * runtime kept its sessions as conversations before, and those stay as they
 * were written.
 */
public final class FileConversationRepository implements ConversationRepository {

    static final String THREADS = "conversation-threads";
    private static final String FILE_NAME = "conversation.json";

    private final Documents<ConversationId, Conversation> conversations;

    public FileConversationRepository(Path base, ObjectMapper json, TenantScope scope) {
        this.conversations = Documents.of(Conversation.class)
                .path((ConversationId id) -> THREADS + "/" + id.value() + "/" + FILE_NAME)
                .build(() -> FileRepo.open(base, scope.tenant().namespace()), json);
    }

    @Override
    public Conversation save(Conversation conversation) {
        return conversations.put(conversation.id(), conversation);
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return conversations.find(id);
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return conversations.findAll(THREADS, FILE_NAME).stream()
                .sorted(Comparator.comparing(Conversation::createdAt).reversed())
                .skip(page.offset())
                .limit(page.size())
                .toList();
    }

    @Override
    public void deleteById(ConversationId id) {
        conversations.delete(id);
    }
}
