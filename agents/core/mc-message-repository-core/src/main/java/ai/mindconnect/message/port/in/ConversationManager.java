package ai.mindconnect.message.port.in;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.domain.ParticipantType;

import java.util.List;
import java.util.Optional;

public interface ConversationManager {

    /**
     * Creates the conversation {@code id}; the caller draws the id first
     * ({@code ConversationId.random()}) because the participants
     * it hands in are keyed by it.
     */
    Conversation createConversation(ConversationId id, String topic,
                                    ConversationType type, List<Participant> participants);

    Optional<Conversation> findById(ConversationId id);

    List<Conversation> list(PageRequest page);

    List<Message> loadHistory(ConversationId conversation, PageRequest page);

    /**
     * The WHOLE conversation as a typed {@link ConversationHistory} — the
     * turn runtime's way in. Use this (not a MAX_VALUE page) wherever the
     * complete history is the requirement, so the completeness contract is
     * visible in the type.
     */
    ConversationHistory loadCompleteHistory(ConversationId conversation);

    /**
     * Adds a message to a conversation, tagged with the agent chat-turn
     * that produced it.
     * <p>
     * All messages of the same turn (user prompt, assistant tool calls +
     * results, final answer) share the same {@code turnId} so they can be
     * cross-referenced with the LLM call traces persisted under that turn.
     * {@code null} is allowed for messages that originate outside an agent
     * turn (broadcasts, test fixtures, etc.).
     */
    Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                     MessageType type, String content, ChatTurnId turnId);

    /**
     * Same, with metadata. The tool/approval types REQUIRE their pairing keys
     * here ({@code callId}, {@code callIds}, {@code requestId} — see
     * {@link MessageType}): the runtime derives open calls and pending
     * approvals from metadata, never by parsing content.
     */
    Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                     MessageType type, String content, ChatTurnId turnId, Integer run,
                                     java.util.Map<String, Object> metadata);

    /**
     * Same, for a message made of {@link ContentPart}s — text plus the images
     * and files sent with it. The record's {@code content} is derived from the
     * text parts, so the text-only readers see the message as before.
     */
    Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                     MessageType type, List<ContentPart> parts, ChatTurnId turnId, Integer run,
                                     java.util.Map<String, Object> metadata);

    /**
     * Marks an existing message as compressed.
     * The original content is preserved; {@code stub} is what the LLM sees in
     * subsequent turns via {@link ai.mindconnect.message.domain.Message#compressedContent()}.
     */
    void compressMessage(ConversationId conversation, MessageId message, String stub, Integer compressedTokenCount);

    /**
     * Updates the estimated token count for an existing message.
     * No-op if the message is not found.
     */
    void updateTokenCount(ConversationId conversation, MessageId message, int tokenCount);

    /**
     * Records the wall-clock duration that produced this message, in milliseconds.
     * No-op if the message is not found.
     */
    void updateDurationMs(ConversationId conversation, MessageId message, long durationMs);

    /**
     * Permanently deletes all messages in the conversation whose sequenceNum
     * is in [{@code fromSeq}, {@code toSeq}] inclusive.
     *
     * @return number of messages deleted
     */
    int deleteMessages(ConversationId conversation, int fromSeq, int toSeq);
}
