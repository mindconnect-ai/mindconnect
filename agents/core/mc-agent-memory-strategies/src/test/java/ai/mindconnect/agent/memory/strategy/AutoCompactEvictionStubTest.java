package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.domain.SummaryPlacement;
import ai.mindconnect.agent.runtime.memory.domain.ToolResultEvictionPolicy;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.port.out.TokenCounters;
import ai.mindconnect.agent.runtime.service.MessageToLlmMessageMapper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An evicted tool result tells the model the id to hand to {@code fetch_tool_result},
 * and that tool reads it back as a message id of the same conversation. The stub
 * must therefore carry the bare value — the qualified form ({@code conversation/value})
 * is not a valid message id and the reload fails.
 */
class AutoCompactEvictionStubTest {

    private static final Pattern STUB_ID = Pattern.compile("Tool result evicted from context — id=([^,]+),");

    private final ConversationId conversationId = ConversationId.random();
    private final UserId userId = UserId.of("u");
    private int seq;

    @Test
    void theEvictionStubCarriesAnIdFetchToolResultCanReadBack() {
        Message call = message(ParticipantType.AGENT, MessageType.TOOL_CALL,
                "{\"toolCalls\":[{\"name\":\"web_search\",\"arguments\":{},\"id\":\"c1\"}]}",
                Map.of("callIds", List.of("c1")));
        Message result = message(ParticipantType.AGENT, MessageType.TOOL_RESULT,
                "{\"toolCallId\":\"c1\",\"toolName\":\"web_search\",\"result\":\"" + "x".repeat(2_000) + "\"}",
                Map.of("callId", "c1", "toolName", "web_search")).withTokenCount(500);
        List<Message> history = List.of(
                message(ParticipantType.USER, MessageType.CHAT, "search something", Map.of()),
                call,
                result,
                message(ParticipantType.AGENT, MessageType.CHAT, "here is what I found", Map.of()),
                message(ParticipantType.USER, MessageType.CHAT, "and now something else", Map.of()));

        List<LlmMessage> window = strategy().buildWindow(def(), session(), AuthenticationInfo.of(userId), history);

        String rendered = window.toString();
        Matcher stub = STUB_ID.matcher(rendered);
        assertThat(stub.find()).as("an eviction stub in the window: %s", rendered).isTrue();
        assertThat(MessageId.of(stub.group(1).trim())).isEqualTo(result.id());
    }

    private AutoCompactStrategy strategy() {
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
        ConversationSummaryRepository summaries = new ConversationSummaryRepository() {
            @Override public void save(ConversationSummary summary) { }
            @Override public List<ConversationSummary> findByConversation(ConversationId conversation) { return List.of(); }
            @Override public void deleteByConversation(ConversationId conversation) { }
        };
        AutoCompactConfig cfg = new AutoCompactConfig(0.8, SummaryPlacement.SYSTEM_PROMPT,
                new ToolResultEvictionPolicy(1, 100));
        return new AutoCompactStrategy(cfg, null, summaries, null, counters, configs, new MessageToLlmMessageMapper());
    }

    private AgentDefinition def() {
        return AgentDefinition.create("a", "d", "p", null, "llm");
    }

    private AgentSession session() {
        return AgentSession.startSubAgent(AgentId.random(), userId, conversationId, null, null, null);
    }

    private Message message(ParticipantType sender, MessageType type, String content, Map<String, Object> metadata) {
        return Message.of(conversationId, UUID.randomUUID().toString(), sender, type, content, ++seq)
                .withMetadata(metadata);
    }
}
