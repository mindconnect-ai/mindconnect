package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.capabilities.memory.ConversationSummary;
import ai.mindconnect.agent.runtime.capabilities.memory.SummaryStore;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * Summaries in the application's summary repository. That one says which
 * part of a conversation a summary covers by sequence number, this runtime
 * by the id of the last message — the message repository translates.
 */
final class RepositorySummaryStore implements SummaryStore {

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final ConversationSummaryRepository summaries;
    private final MessageRepository messages;
    private final Supplier<SessionStore> sessions;

    RepositorySummaryStore(ConversationSummaryRepository summaries, MessageRepository messages,
                           Supplier<SessionStore> sessions) {
        this.summaries = summaries;
        this.messages = messages;
        this.sessions = sessions;
    }

    /** The conversation the session's messages are in, which need not be the one under its own id. */
    private ConversationId conversationOf(SessionId sessionId) {
        return sessions.get().find(sessionId).map(RepositorySessionStore::conversationOf)
                .orElseGet(() -> ConversationId.of(sessionId.value()));
    }

    @Override
    public Optional<ConversationSummary> find(SessionId sessionId) {
        ConversationId conversation = conversationOf(sessionId);
        return summaries.findByConversation(conversation).stream()
                .max(Comparator.comparingInt(
                        ai.mindconnect.agent.runtime.memory.domain.ConversationSummary::toSequenceNum))
                .flatMap(latest -> messages.findByConversation(conversation, EVERYTHING).stream()
                        .filter(message -> message.sequenceNum() == latest.toSequenceNum())
                        .findFirst()
                        .map(last -> new ConversationSummary(latest.content(), last.id().value())));
    }

    /** The new summary contains the one before, so it takes its place. */
    @Override
    public void save(SessionId sessionId, ConversationSummary summary) {
        ConversationId conversation = conversationOf(sessionId);
        int through = messages.findById(conversation, MessageId.of(summary.throughMessageId()))
                .map(Message::sequenceNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session " + sessionId.value() + " has no message " + summary.throughMessageId()));
        summaries.deleteByConversation(conversation);
        summaries.save(ai.mindconnect.agent.runtime.memory.domain.ConversationSummary.create(
                conversation, 1, through, through, summary.text()));
    }
}
