package ai.mindconnect.conversation.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ContentPart;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.ConversationStatus;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.domain.Participant;
import ai.mindconnect.conversation.domain.ParticipantId;
import ai.mindconnect.conversation.domain.ParticipantType;
import ai.mindconnect.conversation.port.in.ConversationManager;
import ai.mindconnect.conversation.port.out.ConversationChannel;
import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.conversation.port.out.MessageRepository;

/** Conversations on their two repositories, with the channels they may happen in. */
public final class ConversationService implements ConversationManager {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final List<ConversationChannel> channels;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               List<ConversationChannel> channels, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.channels = List.copyOf(channels);
        this.clock = clock;
    }

    @Override
    public Conversation open(String topic, String channel, Map<String, Object> metadata) {
        if (channel != null && channelNamed(channel).isEmpty()) {
            throw new IllegalArgumentException("There is no channel " + channel);
        }
        return conversations.save(new Conversation(ConversationId.random(), topic, channel, ConversationStatus.OPEN,
                List.of(), metadata, clock.instant(), clock.instant()));
    }

    @Override
    public Optional<Conversation> find(ConversationId conversation) {
        return conversations.findById(conversation);
    }

    @Override
    public List<Conversation> list(PageRequest page) {
        return conversations.findAll(page);
    }

    @Override
    public Participant join(ConversationId conversation, ParticipantType type, String refId, String displayName,
                            Map<String, Object> metadata) {
        Conversation found = existing(conversation);
        Participant joined = new Participant(ParticipantId.random(), conversation, type, refId, displayName, metadata,
                clock.instant(), null);
        conversations.save(found.with(joined, clock.instant()));
        return joined;
    }

    @Override
    public void leave(ConversationId conversation, ParticipantId participant) {
        Conversation found = existing(conversation);
        found.participant(participant).filter(Participant::present).ifPresent(present ->
                conversations.save(found.with(present.leaving(clock.instant()), clock.instant())));
    }

    @Override
    public Message post(ConversationId conversation, ParticipantId from, List<ContentPart> content,
                        Map<String, Object> metadata) {
        Conversation found = existing(conversation);
        Message posted = write(found, from, content, metadata);
        if (found.channel() != null) {
            channelNamed(found.channel()).ifPresent(channel -> channel.deliver(found, posted));
        }
        return posted;
    }

    @Override
    public Message receive(ConversationId conversation, ParticipantId from, List<ContentPart> content,
                           Map<String, Object> metadata) {
        return write(existing(conversation), from, content, metadata);
    }

    private Message write(Conversation conversation, ParticipantId from, List<ContentPart> content,
                          Map<String, Object> metadata) {
        if (conversation.participant(from).filter(Participant::present).isEmpty()) {
            throw new IllegalArgumentException(
                    "Participant " + from + " takes no part in conversation " + conversation.id());
        }
        return messages.append(conversation.id(), place -> new Message(MessageId.random(), conversation.id(), place,
                from, content, clock.instant(), metadata));
    }

    @Override
    public List<Message> history(ConversationId conversation, PageRequest page) {
        return messages.findByConversation(conversation, page);
    }

    @Override
    public void close(ConversationId conversation) {
        conversations.save(existing(conversation).withStatus(ConversationStatus.CLOSED, clock.instant()));
    }

    @Override
    public void delete(ConversationId conversation) {
        messages.deleteByConversation(conversation);
        conversations.deleteById(conversation);
    }

    private Conversation existing(ConversationId conversation) {
        return conversations.findById(conversation)
                .orElseThrow(() -> new IllegalArgumentException("There is no conversation " + conversation));
    }

    private Optional<ConversationChannel> channelNamed(String name) {
        return channels.stream().filter(channel -> channel.name().equals(name)).findFirst();
    }
}
