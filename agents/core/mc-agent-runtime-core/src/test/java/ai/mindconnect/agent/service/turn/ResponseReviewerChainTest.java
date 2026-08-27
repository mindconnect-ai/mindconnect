package ai.mindconnect.agent.service.turn;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response-reviewer chain in isolation — scripted reviewer agents, no
 * LLM: PASS conventions, rewrites, the BLOCK: prefix stopping the chain,
 * ordering, fail-open on reviewer errors, and the last_messages view the
 * reviewers judge against.
 */
class ResponseReviewerChainTest {

    private final UUID conversationId = UUID.randomUUID();
    private final InMemoryConversations conversations = new InMemoryConversations(conversationId);
    private final List<StreamEvent> events = new ArrayList<>();

    /** reviewerName → what it answers; records every invocation with its variables. */
    private final Map<String, Function<Map<String, Object>, String>> script = new LinkedHashMap<>();
    private final List<String> invoked = new ArrayList<>();
    private final Map<String, Map<String, Object>> seenVariables = new HashMap<>();

    private final AgentTaskRunner runner = new AgentTaskRunner() {
        @Override public String run(String task, String userMessage) {
            return run(task, userMessage, Map.of());
        }
        @Override public String run(String task, String userMessage, Map<String, Object> variables) {
            invoked.add(task);
            seenVariables.put(task, variables);
            return script.get(task).apply(variables);
        }
    };

    private AgentDefinition defWithReviewers(String... reviewers) {
        AgentDefinition base = AgentDefinition.create(new Namespace("test"), "a", "d", "p", null, "llm");
        return base.withBasicFields(base.namespace(), base.name(), base.description(),
                base.systemPrompt(), base.welcomeMessage(), base.llmConfigName(),
                base.maxIterations(), List.of(reviewers));
    }

    private String run(AgentDefinition def, String draft) {
        return new ResponseReviewerChain(runner, conversations, def,
                "the question", draft, conversationId, null, events::add).run();
    }

    private List<StreamEvent.ReviewerVerdict> verdicts() {
        return events.stream()
                .filter(e -> e instanceof StreamEvent.ReviewerDecision)
                .map(e -> ((StreamEvent.ReviewerDecision) e).verdict())
                .toList();
    }

    // ── verdicts ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"PASS", "pass", "PASS: looks good", "PASS - fine", "`PASS`", "  PASS  "})
    void passVariantsLeaveTheDraftUntouched(String reviewerAnswer) {
        script.put("rev", vars -> reviewerAnswer);

        String result = run(defWithReviewers("rev"), "the draft");

        assertThat(result).isEqualTo("the draft");
        assertThat(verdicts()).containsExactly(StreamEvent.ReviewerVerdict.PASSED);
    }

    @Test
    void aRewriteReplacesTheAnswer() {
        script.put("rev", vars -> "  a better answer  ");

        String result = run(defWithReviewers("rev"), "the draft");

        assertThat(result).isEqualTo("a better answer");
        assertThat(verdicts()).containsExactly(StreamEvent.ReviewerVerdict.MODIFIED);
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.ResponseRevised r && !r.blocked());
    }

    @Test
    void blockReplacesTheAnswerAndStopsTheChain() {
        script.put("guard", vars -> "BLOCK: I cannot share that.");
        script.put("late", vars -> "should never run");

        String result = run(defWithReviewers("guard", "late"), "the secret draft");

        assertThat(result).isEqualTo("I cannot share that.");
        assertThat(invoked).as("the chain stops at the block").containsExactly("guard");
        assertThat(verdicts()).containsExactly(StreamEvent.ReviewerVerdict.BLOCKED);
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.ResponseRevised r && r.blocked());
    }

    @Test
    void reviewersRunInOrderAndEachSeesThePredecessorsOutput() {
        script.put("first", vars -> "after first");
        script.put("second", vars -> vars.get("agent_response") + " + second");

        String result = run(defWithReviewers("first", "second"), "the draft");

        assertThat(invoked).containsExactly("first", "second");
        assertThat(seenVariables.get("first").get("agent_response")).isEqualTo("the draft");
        assertThat(seenVariables.get("second").get("agent_response")).isEqualTo("after first");
        assertThat(result).isEqualTo("after first + second");
    }

    @Test
    void aFailingReviewerFailsOpenAndTheChainContinues() {
        script.put("broken", vars -> { throw new RuntimeException("reviewer agent crashed"); });
        script.put("next", vars -> "PASS");

        String result = run(defWithReviewers("broken", "next"), "the draft");

        assertThat(result).as("the draft survives a crashing reviewer").isEqualTo("the draft");
        assertThat(invoked).as("the chain continues past the failure").containsExactly("broken", "next");
        assertThat(verdicts()).containsExactly(
                StreamEvent.ReviewerVerdict.PASSED, StreamEvent.ReviewerVerdict.PASSED);
    }

    @Test
    void aNullAnswerKeepsTheDraft() {
        script.put("mute", vars -> null);

        assertThat(run(defWithReviewers("mute"), "the draft")).isEqualTo("the draft");
        assertThat(verdicts()).containsExactly(StreamEvent.ReviewerVerdict.PASSED);
    }

    @Test
    void noReviewersMeansNoWork() {
        assertThat(run(defWithReviewers(), "the draft")).isEqualTo("the draft");
        assertThat(events).isEmpty();
        assertThat(invoked).isEmpty();
    }

    // ── the last_messages view ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void reviewersSeeOnlyRecentChatMessagesWithoutToolsOrTheCurrentQuestion() {
        // 12 chat exchanges + tool noise + the just-persisted current question.
        for (int i = 1; i <= 12; i++) {
            conversations.append(ParticipantType.USER, MessageType.CHAT, "q" + i);
            conversations.append(ParticipantType.AGENT, MessageType.TOOL_CALL, "{\"toolCalls\":[]}");
            conversations.append(ParticipantType.AGENT, MessageType.TOOL_RESULT, "{\"result\":\"x\"}");
            conversations.append(ParticipantType.AGENT, MessageType.CHAT, "a" + i);
        }
        Message current = conversations.append(ParticipantType.USER, MessageType.CHAT, "the question");
        script.put("rev", vars -> "PASS");

        new ResponseReviewerChain(runner, conversations, defWithReviewers("rev"),
                "the question", "the draft", conversationId, current.id(), events::add).run();

        var lastMessages = (List<ResponseReviewerChain.MessageView>)
                seenVariables.get("rev").get("last_messages");
        assertThat(lastMessages).hasSize(10);                       // capped
        assertThat(lastMessages).allMatch(v -> v.role().equals("user") || v.role().equals("agent"));
        assertThat(lastMessages).noneMatch(v -> v.content().equals("the question"));  // current excluded
        assertThat(lastMessages.get(lastMessages.size() - 1).content()).isEqualTo("a12");
    }

    // ── fake store (only what the chain reads) ──────────────────────────────

    private static final class InMemoryConversations implements ConversationManager {
        private final UUID conversationId;
        private final List<Message> messages = new ArrayList<>();
        private int seq;

        private InMemoryConversations(UUID conversationId) {
            this.conversationId = conversationId;
        }

        Message append(ParticipantType sender, MessageType type, String content) {
            Message m = Message.of(conversationId, UUID.randomUUID(), sender, type, content, ++seq);
            messages.add(m);
            return m;
        }

        @Override public List<Message> loadHistory(UUID id, PageRequest page) {
            return List.copyOf(messages);
        }

        @Override public ConversationHistory loadCompleteHistory(UUID id) {
            return ConversationHistory.of(id, List.copyOf(messages));
        }

        @Override public Conversation createConversation(Namespace namespace, String title,
                ConversationType type, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Conversation> findById(UUID id) { return Optional.empty(); }
        @Override public List<Conversation> listByNamespace(Namespace namespace, PageRequest page) {
            return List.of();
        }
        @Override public Message addMessageToConversation(UUID id, UUID senderId,
                ParticipantType senderType, MessageType type, String content, UUID turnId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(UUID id, UUID senderId,
                ParticipantType senderType, MessageType type, String content, UUID turnId,
                Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public void compressMessage(UUID id, UUID messageId, String stub, Integer tokens) { }
        @Override public void updateTokenCount(UUID id, UUID messageId, int tokenCount) { }
        @Override public void updateDurationMs(UUID id, UUID messageId, long durationMs) { }
        @Override public int deleteMessages(UUID id, int fromSeq, int toSeq) { return 0; }
    }
}
