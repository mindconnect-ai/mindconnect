package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Comparator;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
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

    RepositorySummaryStore(ConversationSummaryRepository summaries, MessageRepository messages) {
        this.summaries = summaries;
        this.messages = messages;
    }

    @Override
    public Optional<ConversationSummary> find(SessionId sessionId) {
        ConversationId conversation = ConversationId.of(sessionId.value());
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
        ConversationId conversation = ConversationId.of(sessionId.value());
        int through = messages.findById(conversation, MessageId.of(summary.throughMessageId()))
                .map(Message::sequenceNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session " + sessionId.value() + " has no message " + summary.throughMessageId()));
        summaries.deleteByConversation(conversation);
        summaries.save(ai.mindconnect.agent.runtime.memory.domain.ConversationSummary.create(
                conversation, 1, through, through, summary.text()));
    }
}
