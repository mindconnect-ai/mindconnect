package ai.mindconnect.message.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turn grouping: a user CHAT opens a turn — unless it carries the open
 * turn's id, then it continues it (the runtime inserts such messages on the
 * user's behalf mid-turn). Messages without turn ids keep the old rule.
 */
class ConversationHistoryTurnsTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private static Message user(int seq, UUID turnId) {
        return Message.of(CONVERSATION, UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, "u" + seq, seq)
                .withTurnId(turnId);
    }

    private static Message agent(int seq, UUID turnId) {
        return Message.of(CONVERSATION, AGENT, ParticipantType.AGENT, MessageType.CHAT, "a" + seq, seq)
                .withTurnId(turnId);
    }

    @Test
    void aUserMessageSharingTheOpenTurnsIdContinuesTheTurn() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Message> messages = List.of(
                user(1, first), agent(2, first),
                user(3, second), user(4, second), agent(5, second));

        ConversationHistory history = ConversationHistory.of(CONVERSATION, messages);

        assertThat(history.turns()).hasSize(2);
        assertThat(history.currentTurn()).get().extracting(ChatTurn::turnId).isEqualTo(second);
        assertThat(history.currentTurn().orElseThrow().messages()).extracting(Message::sequenceNum)
                .containsExactly(3, 4, 5);
        assertThat(ConversationHistory.currentTurnMessages(messages)).extracting(Message::sequenceNum)
                .containsExactly(3, 4, 5);
    }

    @Test
    void aUserMessageWithAnotherOrNoTurnIdOpensATurn() {
        UUID first = UUID.randomUUID();
        List<Message> messages = List.of(user(1, first), agent(2, first), user(3, null), user(4, UUID.randomUUID()));

        assertThat(ConversationHistory.of(CONVERSATION, messages).turns()).hasSize(3);
        assertThat(ConversationHistory.currentTurnMessages(messages)).extracting(Message::sequenceNum)
                .containsExactly(4);
    }

    @Test
    void legacyMessagesWithoutTurnIdsOpenATurnEach() {
        List<Message> messages = List.of(user(1, null), agent(2, null), user(3, null), agent(4, null));

        assertThat(ConversationHistory.of(CONVERSATION, messages).turns()).hasSize(2);
        assertThat(ConversationHistory.currentTurnMessages(messages)).extracting(Message::sequenceNum)
                .containsExactly(3, 4);
    }

    @Test
    void beforeAnyUserMessageTheWholeListIsTheCurrentEpisode() {
        List<Message> messages = List.of(agent(1, null));

        assertThat(ConversationHistory.of(CONVERSATION, messages).turns()).isEmpty();
        assertThat(ConversationHistory.currentTurnMessages(messages)).isSameAs(messages);
    }
}
