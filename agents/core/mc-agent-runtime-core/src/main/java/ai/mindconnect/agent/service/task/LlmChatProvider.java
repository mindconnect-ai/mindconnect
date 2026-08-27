package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.domain.TraceContext;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.service.prompt.SystemPromptRenderer;
import ai.mindconnect.agent.service.round.LlmAnswer;
import ai.mindconnect.agent.service.round.LlmProvider;
import ai.mindconnect.agent.service.round.TurnMessage;
import ai.mindconnect.agent.service.round.Usage;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.message.domain.Message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link LlmProvider} over the streaming LLM gateway — one call per round,
 * bound to one turn. This is BOTH seams of concept 16 in one adapter:
 *
 * <ul>
 *   <li><b>Rendering.</b> The loop's history list is the truth; what the
 *       model sees is built here, fresh every round: the system prompt
 *       (re-rendered, so a summary addendum written mid-turn shows up) plus
 *       the memory strategy's window (compression stubs and all). A
 *       compaction between two rounds simply takes effect on the next call.</li>
 *   <li><b>Streaming.</b> Text deltas go out as {@link StreamEvent.Token} on
 *       the turn's stream; tool-call and thinking deltas are reassembled by
 *       index and come back as ONE {@code TOOL_CALL} message, its content in
 *       exactly the shape the old persister wrote — history stays readable
 *       for both worlds.</li>
 * </ul>
 *
 * <p>Cancellation is the loop's handle passed straight into the gateway,
 * which registers its abort hook on it — the in-flight HTTP call dies with
 * the task, not with the last token.
 */
public final class LlmChatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmChatProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmChat llmChat;
    private final AgentDefinition def;
    private final AgentSession session;
    private final MemoryStrategy memoryStrategy;
    private final PromptRenderer promptRenderer;
    private final Consumer<StreamEvent> stream;
    /** Nullable — no repository, no tracing. */
    private final LlmCallTraceRepository traceRepository;
    private final TraceContext traceContext;

    public LlmChatProvider(LlmChat llmChat, AgentDefinition def, AgentSession session,
                           MemoryStrategy memoryStrategy, PromptRenderer promptRenderer,
                           Consumer<StreamEvent> stream,
                           LlmCallTraceRepository traceRepository, TraceContext traceContext) {
        this.llmChat = llmChat;
        this.def = def;
        this.session = session;
        this.memoryStrategy = memoryStrategy;
        this.promptRenderer = promptRenderer;
        this.stream = stream;
        this.traceRepository = traceRepository;
        this.traceContext = traceContext;
    }

    @Override
    public LlmAnswer ask(String requestId, UUID sessionId, List<Message> history,
                         List<ToolDefinition> toolDefinitions, Cancellation cancellation) {
        stream.accept(new StreamEvent.AskingLlm());
        LlmRequest request = LlmRequest.streaming(def.llmConfigName(), window(history), toolDefinitions);

        RoundAccumulator round = new RoundAccumulator();
        llmChat.chatStreaming(request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta td -> {
                    round.text.append(td.text());
                    stream.accept(new StreamEvent.Token(td.text()));
                }
                case LlmStreamChunk.ToolCallDelta tcd -> round.toolCallBuilders
                        .computeIfAbsent(tcd.index(), i -> new ToolCallBuilder())
                        .feed(tcd);
                case LlmStreamChunk.ThinkingDelta thd -> round.thinkingBuilders
                        .computeIfAbsent(thd.index(), i -> new ThinkingBlockBuilder())
                        .feed(thd);
                case LlmStreamChunk.Done done -> round.finish(done);
            }
        }, cancellation, traceListener());

        return round.toAnswer();
    }

    /**
     * The model's sight of the truth: system prompt first (rendered fresh —
     * the strategy's addendum may have changed since the last round), then
     * the strategy's window RENDERED FROM the loop's history list — the one
     * load the execution made, kept current by every append. The strategy
     * does not reload (concept 16: read once per execution).
     */
    private List<LlmMessage> window(List<Message> history) {
        AuthenticationInfo auth = AuthenticationInfo.of(session.userId(), session.namespace());
        List<LlmMessage> window = new ArrayList<>();
        window.add(LlmMessage.system(
                SystemPromptRenderer.render(promptRenderer, memoryStrategy, def, session, auth)));
        window.addAll(memoryStrategy.buildWindow(def, session, auth, history));
        return window;
    }

    // ── one round's accumulation ────────────────────────────────────────────

    private static final class RoundAccumulator {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();
        final Map<Integer, ThinkingBlockBuilder> thinkingBuilders = new TreeMap<>();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final List<ThinkingBlock> thinkingBlocks = new ArrayList<>();
        Usage usage = Usage.ZERO;
        boolean truncated;

        void finish(LlmStreamChunk.Done done) {
            for (ThinkingBlockBuilder builder : thinkingBuilders.values()) {
                ThinkingBlock built = builder.build();
                if (built != null) thinkingBlocks.add(built);
            }
            for (ToolCallBuilder builder : toolCallBuilders.values()) {
                ToolCall built = builder.build();
                if (built != null) toolCalls.add(built);
            }
            usage = new Usage(done.inputTokens(), done.outputTokens());
            truncated = done.finishReason() == FinishReason.LENGTH;
        }

        LlmAnswer toAnswer() {
            if (toolCalls.isEmpty()) {
                return new LlmAnswer(List.of(TurnMessage.assistant(text.toString().trim())),
                        usage, truncated);
            }
            return new LlmAnswer(List.of(TurnMessage.toolCalls(
                    toolCallContent(thinkingBlocks, toolCalls),
                    toolCalls.stream().map(ToolCall::id).toList())), usage, truncated);
        }
    }

    /**
     * The stored TOOL_CALL content, in the exact shape the previous runtime
     * wrote ({@code thinkingBlocks} first — they must replay before the calls;
     * Gemini {@code thoughtSignature}s survive the round trip).
     */
    private static String toolCallContent(List<ThinkingBlock> thinkingBlocks, List<ToolCall> toolCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!thinkingBlocks.isEmpty()) {
            payload.put("thinkingBlocks", thinkingBlocks.stream().map(tb -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("type", tb.type());
                if (tb.text() != null) entry.put("text", tb.text());
                if (tb.data() != null) entry.put("data", tb.data());
                if (tb.signature() != null) entry.put("signature", tb.signature());
                return entry;
            }).toList());
        }
        payload.put("toolCalls", toolCalls.stream().map(tc -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", tc.id());
            entry.put("name", tc.name());
            entry.put("arguments", tc.arguments());
            if (tc.thoughtSignature() != null) entry.put("thoughtSignature", tc.thoughtSignature());
            return entry;
        }).toList());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialise tool-call list: {}", e.getMessage());
            return "{\"toolCalls\":[]}";
        }
    }

    // ── streaming reassembly (unchanged mechanics from the old loop) ────────

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private String thoughtSignature;
        private final StringBuilder argsJson = new StringBuilder();

        void feed(LlmStreamChunk.ToolCallDelta delta) {
            if (delta.id() != null) id = delta.id();
            if (delta.name() != null) name = delta.name();
            if (delta.thoughtSignature() != null) thoughtSignature = delta.thoughtSignature();
            if (delta.argumentsFragment() != null) argsJson.append(delta.argumentsFragment());
        }

        ToolCall build() {
            if (id == null || name == null) {
                log.warn("Discarding incomplete tool-call delta (id={}, name={})", id, name);
                return null;
            }
            try {
                Map<String, Object> args = argsJson.isEmpty()
                        ? new HashMap<>()
                        : MAPPER.readValue(argsJson.toString(), new TypeReference<>() { });
                return new ToolCall(id, name, args, thoughtSignature);
            } catch (Exception e) {
                log.warn("Failed to parse tool-call arguments JSON for '{}': {} — raw: {}",
                        name, e.getMessage(), argsJson);
                return new ToolCall(id, name, new HashMap<>(), thoughtSignature);
            }
        }
    }

    private static final class ThinkingBlockBuilder {
        private String type;
        private String signature;
        private String data;
        private final StringBuilder text = new StringBuilder();

        void feed(LlmStreamChunk.ThinkingDelta delta) {
            if (delta.type() != null) type = delta.type();
            if (delta.signature() != null) signature = delta.signature();
            if (delta.data() != null) data = delta.data();
            if (delta.textFragment() != null) text.append(delta.textFragment());
        }

        ThinkingBlock build() {
            if (type == null) {
                log.warn("Discarding thinking block with no type");
                return null;
            }
            return new ThinkingBlock(type, text.isEmpty() ? null : text.toString(), data, signature);
        }
    }

    private LlmCallListener traceListener() {
        if (traceRepository == null) return LlmCallListener.NOOP;
        return event -> {
            try {
                traceRepository.save(LlmCallTrace.of(traceContext, event));
            } catch (Exception e) {
                log.warn("LLM trace save failed: {}", e.getMessage());
            }
        };
    }
}
