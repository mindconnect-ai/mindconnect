package ai.mindconnect.message.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The COMPLETE loaded history of one conversation — the type that says what a
 * bare {@code List<Message>} cannot: this is the whole conversation as of
 * loading, kept current via {@link #append}. Obtained from
 * {@code ConversationManager.loadCompleteHistory}; the turn loop holds one
 * instance per execution and renders every model window from it, so the store
 * is read once instead of once per round.
 *
 * <p>Structured access: {@link #turns()} groups the messages into
 * {@link ChatTurn}s (user → assistant exchanges); {@link #currentTurn()} is
 * what execution decisions derive from (the ToolCalls fold, task-id
 * derivation, cancel). Slicing a turn out is deliberate and safe — only the
 * memory strategies need the whole-conversation guarantee, and they take THIS
 * type.
 *
 * <p>Not thread-safe: one instance belongs to one turn execution.
 */
public final class ConversationHistory {

    private final UUID conversationId;
    private final List<Message> messages;

    private ConversationHistory(UUID conversationId, List<Message> messages) {
        this.conversationId = conversationId;
        this.messages = new ArrayList<>(messages);
        this.messages.sort(Comparator.comparingInt(Message::sequenceNum));
    }

    /**
     * Wraps an already loaded, COMPLETE message list. The contract is the
     * caller's to honour — production code gets its instances from
     * {@code ConversationManager.loadCompleteHistory}, which loads everything;
     * this factory exists for that implementation and for tests.
     */
    public static ConversationHistory of(UUID conversationId, List<Message> messages) {
        return new ConversationHistory(conversationId, messages);
    }

    public UUID conversationId() {
        return conversationId;
    }

    /** Flat view, sequence order — what mappers and strategies iterate. */
    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** A message the turn just persisted — keeps this instance the live truth. */
    public void append(Message message) {
        messages.add(message);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * The history grouped into logical turns: each user CHAT opens one, and
     * everything until the next user CHAT belongs to it. Messages before the
     * first user CHAT (seeding, migration) are not part of any turn.
     */
    public List<ChatTurn> turns() {
        List<ChatTurn> turns = new ArrayList<>();
        Message opener = null;
        List<Message> current = new ArrayList<>();
        for (Message message : messages) {
            boolean opensTurn = message.type() == MessageType.CHAT
                    && message.senderType() == ParticipantType.USER;
            if (opensTurn) {
                if (opener != null) turns.add(new ChatTurn(opener, current));
                opener = message;
                current = new ArrayList<>();
            }
            if (opener != null) current.add(message);
        }
        if (opener != null) turns.add(new ChatTurn(opener, current));
        return List.copyOf(turns);
    }

    /** The newest turn — the one execution decisions are about. Empty before any user message. */
    public Optional<ChatTurn> currentTurn() {
        List<ChatTurn> turns = turns();
        return turns.isEmpty() ? Optional.empty() : Optional.of(turns.get(turns.size() - 1));
    }

    /** The current turn's id — stable across approval resumes. */
    public Optional<UUID> currentTurnId() {
        return currentTurn().map(ChatTurn::turnId).filter(java.util.Objects::nonNull);
    }

    /** The current turn's highest loop run — 0 until its first approval resume. */
    public int currentRun() {
        return currentTurn().map(ChatTurn::lastRun).orElse(0);
    }

    /** Whether ANY turn holds a TOOL_RESULT for {@code callId}. */
    public boolean hasToolResult(String callId) {
        return messages.stream()
                .anyMatch(m -> m.type() == MessageType.TOOL_RESULT
                        && callId.equals(m.metadata().get("callId")));
    }
}
