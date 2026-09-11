package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.runtime.memory.domain.SummaryPlacement;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.port.out.TokenCounters;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;

import ai.mindconnect.agent.runtime.service.MessageToLlmMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eligibility rules of {@link SummarizingWindowStrategy#compressEligibleToolResults}
 * in isolation — the Claude model, deterministic: never an UNREAD result,
 * never the newest three, only under window pressure, only above the size
 * threshold, and the master switch wins over everything. Compression itself
 * is observed via the recorded {@code compressMessage} calls; nothing here
 * touches an LLM.
 */
class CompressEligibilityTest {

    /** Ratios are validated to (0,1] — this is "pressure from the first token". */
    private static final double PRESSURE_ALWAYS = 0.0001;
    private final ConversationId conversationId = ConversationId.random();
    private final AgentId agentId = AgentId.random();
    private final UserId userId = UserId.of("u");
    /** messageId → stub, recorded by the fake conversation manager. */
    private final Map<MessageId, String> compressed = new LinkedHashMap<>();
    private int seq;

    // ── fixture ─────────────────────────────────────────────────────────────

    private static SummarizingWindowConfig cfg(boolean masterSwitch, double pressureRatio) {
        return new SummarizingWindowConfig(
                0.9, 1.0, masterSwitch,
                10,       // minToolResultCompressTokens → threshold 10 tokens = 40 chars
                0.0001,   // toolResultThresholdRatio (validated to (0,1] — effectively off)
                pressureRatio,
                false, 1.0, SummaryPlacement.SYSTEM_PROMPT, 1_000);
    }

    private SummarizingWindowStrategy strategy(SummarizingWindowConfig cfg) {
        TokenCounter counter = text -> text == null ? 0 : text.length() / 4;
        TokenCounters counters = new TokenCounters() {
            @Override public TokenCounter forModel(String modelName) { return counter; }
            @Override public TokenCounter fallback() { return counter; }
            @Override public void register(String modelPattern, TokenCounter c) { }
        };
        LlmConfigRepository configs = new LlmConfigRepository() {
            @Override public void save(LlmConfig config) { }
            @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
            @Override public Optional<LlmConfig> findByName(String name) { return Optional.empty(); }
            @Override public List<LlmConfig> findAll() { return List.of(); }
            @Override public void deleteById(LlmConfigId id) { }
        };
        return new SummarizingWindowStrategy(cfg,
                new RecordingConversationManager(compressed),
                null,                       // summaries — untouched by this hook
                (toolName, fullResult) -> "STUB",
                null,                       // task runner — untouched by this hook
                counters, configs, new MessageToLlmMessageMapper());
    }

    private AgentDefinition def() {
        return AgentDefinition.create("a", "d", "p", null, "llm");
    }

    private AgentSession session() {
        return AgentSession.startSubAgent(agentId, userId,
                conversationId, null, null, null);
    }

    private int run(SummarizingWindowConfig cfg, List<Message> history) {
        return strategy(cfg).compressEligibleToolResults(def(), session(),
                AuthenticationInfo.of(userId), history);
    }

    // ── history building ────────────────────────────────────────────────────

    private Message user(String text) {
        return message(ParticipantType.USER, MessageType.CHAT, text, Map.of());
    }

    private Message assistant(String text) {
        return message(ParticipantType.AGENT, MessageType.CHAT, text, Map.of());
    }

    /** A ~400-char result — clearly above the 40-char threshold. */
    private Message bigResult(String callId) {
        return result(callId, "x".repeat(400));
    }

    private Message result(String callId, String payload) {
        return message(ParticipantType.AGENT, MessageType.TOOL_RESULT,
                "{\"toolCallId\":\"" + callId + "\",\"toolName\":\"t\",\"result\":\"" + payload + "\"}",
                Map.of("callId", callId));
    }

    private Message message(ParticipantType sender, MessageType type, String content,
                            Map<String, Object> metadata) {
        return Message.of(conversationId, UUID.randomUUID().toString(), sender, type, content, ++seq)
                .withMetadata(metadata);
    }

    /** N read rounds: each big result is followed by an assistant answer. */
    private List<Message> readRounds(int n) {
        List<Message> history = new ArrayList<>();
        history.add(user("go"));
        for (int i = 1; i <= n; i++) {
            history.add(bigResult("c" + i));
            history.add(assistant("answer " + i));
        }
        return history;
    }

    // ── rules ───────────────────────────────────────────────────────────────

    @Test
    void unreadResultsNeverCompressEvenBeyondKeepRecent() {
        // Five results, NONE followed by an assistant message — the wake the
        // execution is about. Nothing may compress, whatever the count.
        List<Message> history = new ArrayList<>();
        history.add(user("go"));
        for (int i = 1; i <= 5; i++) history.add(bigResult("c" + i));

        assertThat(run(cfg(true, PRESSURE_ALWAYS), history)).isZero();
        assertThat(compressed).isEmpty();
    }

    @Test
    void newestThreeStayFullOlderReadResultsCompress() {
        List<Message> history = readRounds(5);

        int marked = run(cfg(true, PRESSURE_ALWAYS), history);

        assertThat(marked).isEqualTo(2);
        List<MessageId> markedIds = List.copyOf(compressed.keySet());
        List<Message> results = history.stream()
                .filter(m -> m.type() == MessageType.TOOL_RESULT).toList();
        assertThat(markedIds).containsExactly(results.get(0).id(), results.get(1).id());
    }

    @Test
    void threeOrFewerResultsNeverCompress() {
        assertThat(run(cfg(true, PRESSURE_ALWAYS), readRounds(3))).isZero();
        assertThat(compressed).isEmpty();
    }

    @Test
    void smallResultsStayFullWhateverTheirAge() {
        List<Message> history = new ArrayList<>();
        history.add(user("go"));
        for (int i = 1; i <= 5; i++) {
            history.add(result("c" + i, "tiny"));   // ~1 token, under the 10-token threshold
            history.add(assistant("answer " + i));
        }

        assertThat(run(cfg(true, PRESSURE_ALWAYS), history)).isZero();
        assertThat(compressed).isEmpty();
    }

    @Test
    void noWindowPressureMeansNoCompression() {
        // Ratio 1.0: the window would have to be beyond 100% of the context
        // window — a handful of small messages never is.
        assertThat(run(cfg(true, 1.0), readRounds(5))).isZero();
        assertThat(compressed).isEmpty();
    }

    @Test
    void masterSwitchOffWinsOverEverything() {
        assertThat(run(cfg(false, PRESSURE_ALWAYS), readRounds(5))).isZero();
        assertThat(compressed).isEmpty();
    }

    @Test
    void alreadyCompressedResultsAreNotRecompressed() {
        List<Message> history = new ArrayList<>(readRounds(5));
        Message first = history.get(1);
        history.set(1, first.withCompressed("old stub", 2));

        int marked = run(cfg(true, PRESSURE_ALWAYS), history);

        assertThat(marked).as("only the second-oldest is newly marked").isEqualTo(1);
        assertThat(compressed).doesNotContainKey(first.id());
    }

    // ── fake store ──────────────────────────────────────────────────────────

    /** Records compressMessage; every other port method is out of this hook's path. */
    private record RecordingConversationManager(Map<MessageId, String> compressed)
            implements ai.mindconnect.message.port.in.ConversationManager {

        @Override public void compressMessage(ConversationId conversationId, MessageId messageId,
                                              String stub, Integer compressedTokenCount) {
            compressed.put(messageId, stub);
        }

        @Override public ai.mindconnect.message.domain.Conversation createConversation(
                ConversationId id, String title,
                ai.mindconnect.message.domain.ConversationType type,
                List<ai.mindconnect.message.domain.Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ai.mindconnect.message.domain.Conversation> findById(ConversationId conversationId) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ai.mindconnect.message.domain.Conversation> list(
                ai.mindconnect.common.PageRequest page) {
            throw new UnsupportedOperationException();
        }
        @Override public List<Message> loadHistory(ConversationId conversationId, ai.mindconnect.common.PageRequest page) {
            throw new UnsupportedOperationException();
        }
        @Override public ai.mindconnect.message.domain.ConversationHistory loadCompleteHistory(ConversationId conversationId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId conversationId, String senderId,
                ParticipantType senderType, MessageType type, String content, ChatTurnId turnId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId conversationId, String senderId,
                ParticipantType senderType, MessageType type, String content, ChatTurnId turnId,
                Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId conversationId, String senderId,
                ParticipantType senderType, MessageType type,
                java.util.List<ai.mindconnect.message.domain.ContentPart> parts, ChatTurnId turnId,
                Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public void updateTokenCount(ConversationId conversationId, MessageId messageId, int tokenCount) {
            throw new UnsupportedOperationException();
        }
        @Override public void updateDurationMs(ConversationId conversationId, MessageId messageId, long durationMs) {
            throw new UnsupportedOperationException();
        }
        @Override public int deleteMessages(ConversationId conversationId, int fromSeq, int toSeq) {
            throw new UnsupportedOperationException();
        }
    }
}
