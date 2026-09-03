package ai.mindconnect.agent.service;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.service.prompt.AttachmentNotice;
import ai.mindconnect.agent.service.ContextTokenBudget;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.message.domain.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates stored {@link Message} domain records into {@link LlmMessage} objects
 * ready to be sent to the LLM.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Structural mapping: CHAT → user/assistant, TOOL_CALL → assistantWithToolCalls,
 *       TOOL_RESULT → tool (using compressed stub if available)</li>
 *   <li>Per-message token guard: truncates any message whose effective content exceeds
 *       {@link ContextTokenBudget#maxMessageTokens()}</li>
 * </ul>
 * <p>
 * No repository access, no summarization, no window logic — pure translation.
 */
public class MessageToLlmMessageMapper implements ai.mindconnect.agent.port.out.LlmMessageMapper {

    private static final Logger log = LoggerFactory.getLogger(MessageToLlmMessageMapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRUNCATION_MARKER =
            "\n[... truncated — content exceeded per-message token limit]";

    /**
     * Maps a list of stored messages to LLM-ready messages, enforcing the per-message
     * token limit from the supplied budget.
     */
    @Override
    public List<LlmMessage> toMessages(List<Message> messages,
                                       AgentDefinition def,
                                       ContextTokenBudget budget) {
        List<LlmMessage> result = new ArrayList<>();
        for (Message m : messages) {
            switch (m.type()) {
                case CHAT -> {
                    boolean assistant = m.senderId().equals(def.id());
                    // A user message that announced attachments (metadata) gets
                    // the notice ahead of its text here, in the model's view —
                    // the stored text stays what the user typed.
                    String text = assistant ? m.content() : AttachmentNotice.forModel(m);
                    String content = guard(text, budget, m.sequenceNum(), "CHAT");
                    result.add(assistant ? LlmMessage.assistant(content) : LlmMessage.user(content));
                }
                case TOOL_CALL -> mapToolCall(m, result);
                case TOOL_RESULT -> mapToolResult(m, result, budget);
                default -> { /* skip SYSTEM and the not-yet-mapped approval/dispatch markers */ }
            }
        }
        return result;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void mapToolCall(Message m, List<LlmMessage> result) {
        try {
            Map<String, Object> payload = MAPPER.readValue(m.content(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawCalls = (List<Map<String, Object>>) payload.get("toolCalls");
            if (rawCalls != null) {
                List<ToolCall> toolCalls = rawCalls.stream()
                        .map(tc -> new ToolCall(
                                (String) tc.get("id"),
                                (String) tc.get("name"),
                                tc.containsKey("arguments")
                                        ? (Map<String, Object>) tc.get("arguments")
                                        : Map.of(),
                                (String) tc.get("thoughtSignature")))
                        .toList();
                // Anthropic thinking blocks (with signatures) that preceded the
                // tool calls — replayed before them so the next request validates.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawThinking =
                        (List<Map<String, Object>>) payload.get("thinkingBlocks");
                List<ThinkingBlock> thinkingBlocks = rawThinking == null ? null
                        : rawThinking.stream()
                            .map(tb -> new ThinkingBlock(
                                    (String) tb.get("type"),
                                    (String) tb.get("text"),
                                    (String) tb.get("data"),
                                    (String) tb.get("signature")))
                            .toList();
                result.add(LlmMessage.assistantWithToolCalls(thinkingBlocks, toolCalls));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialise TOOL_CALL message seq={}: {}", m.sequenceNum(), e.getMessage());
        }
    }

    private void mapToolResult(Message m, List<LlmMessage> result, ContextTokenBudget budget) {
        try {
            Map<String, Object> payload = MAPPER.readValue(m.content(), new TypeReference<>() {});
            String toolCallId = (String) payload.get("toolCallId");
            String toolResult  = (String) payload.get("result");
            if (toolCallId != null && toolResult != null) {
                String contextResult;
                if (m.compressed() && m.compressedContent() != null) {
                    int originalTokens = m.tokenCount() != null ? m.tokenCount() : 0;
                    contextResult = "[Tool result truncated for context (~" + originalTokens
                            + " tokens → summary). The original output is no longer available; "
                            + "do NOT invent values that aren't in the summary below. "
                            + "If the user needs detail not present here, re-run the tool.]\n"
                            + m.compressedContent();
                } else {
                    contextResult = toolResult;
                }
                contextResult = guard(contextResult, budget, m.sequenceNum(), "TOOL_RESULT");
                result.add(LlmMessage.tool(toolCallId, contextResult));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialise TOOL_RESULT message seq={}: {}", m.sequenceNum(), e.getMessage());
        }
    }

    /**
     * Truncates {@code text} to {@link ContextTokenBudget#maxMessageTokens()} if it exceeds
     * the limit. Uses binary search on character offsets for efficiency.
     */
    private String guard(String text, ContextTokenBudget budget, int seqNum, String type) {
        int limit = budget.maxMessageTokens();
        if (budget.counter().countText(text) <= limit) return text;

        int lo = 0, hi = text.length();
        while (lo < hi - 1) {
            int mid = (lo + hi) / 2;
            if (budget.counter().countText(text.substring(0, mid)) <= limit) lo = mid;
            else hi = mid;
        }
        log.warn("MessageMapper: seq={} type={} truncated to ~{} tokens", seqNum, type, limit);
        return text.substring(0, lo) + TRUNCATION_MARKER;
    }
}
