package ai.mindconnect.agent.runtime.engine.adapter.llm;

import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.llm.domain.LlmConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.llm.port.in.LlmChat;

/**
 * {@link ModelGateway} over the LLM gateway's streaming chat. The request's
 * model name is the name of an LLM config there — which provider, key and
 * endpoint stand behind it is the gateway's knowledge.
 *
 * <p>The stream is reassembled here: text and reasoning go out as events
 * while they arrive, tool calls and reasoning blocks are put together by
 * their index and come back as one message. What a provider wants replayed
 * on the next call — signed or encrypted reasoning, a call's thought
 * signature — rides in that message's metadata and is put back when the
 * message is sent again.
 */
public final class LlmChatModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmChatModelGateway.class);
    private static final String THINKING_BLOCKS = "llm.thinkingBlocks";
    private static final String THOUGHT_SIGNATURES = "llm.thoughtSignatures";
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };

    private final LlmChat chat;
    private final ObjectMapper json;
    private final LlmCallTraceListener traces;
    private final Function<String, Optional<LlmConfig>> configurations;
    private final MediaReader media;
    /** The calls underway, each with the handle that closes its connection. */
    private final Map<TurnId, Cancellation> inFlight = new ConcurrentHashMap<>();

    public LlmChatModelGateway(LlmChat chat, ObjectMapper json, LlmCallTraceListener traces,
                               Function<String, Optional<LlmConfig>> configurations, MediaReader media) {
        this.chat = chat;
        this.json = json;
        this.traces = traces;
        this.configurations = configurations;
        this.media = media;
    }

    @Override
    public LlmAnswer ask(ModelRequest request, TurnEventListener events) {
        Cancellation cancellation = new Cancellation();
        inFlight.put(request.turnId(), cancellation);
        StreamedAnswer answer = new StreamedAnswer(request.turnId().value(), events);
        // The gateway reports on a thread of its own; what it reports is handed on
        // from this one, where the turn's namespace is bound.
        List<LlmCallEvent> reported = new CopyOnWriteArrayList<>();
        try {
            chat.chatStreaming(llmRequest(request), answer::accept, cancellation, reported::add);
        } catch (RuntimeException e) {
            // A closed connection surfaces as a failed call; for a call that
            // was told to stop it is the expected end, and what arrived
            // until then is the answer.
            if (!cancellation.isCancelled()) {
                throw e;
            }
            return answer.interrupted();
        } finally {
            inFlight.remove(request.turnId());
            reported.forEach(event -> traces.onCall(request.turnId(), event));
        }
        return cancellation.isCancelled() ? answer.interrupted() : answer.complete();
    }

    @Override
    public OptionalInt contextWindowTokens(ModelSettings settings) {
        return configuration(settings).map(LlmConfig::contextWindowTokens).map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    @Override
    public void cancel(TurnId turnId) {
        Cancellation cancellation = inFlight.get(turnId);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    // ── request ─────────────────────────────────────────────────────────────

    private Optional<LlmConfig> configuration(ModelSettings settings) {
        return settings.model() == null ? Optional.empty() : configurations.apply(settings.model());
    }

    private LlmRequest llmRequest(ModelRequest request) {
        ModelSettings settings = request.settings();
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(request.instructions()));
        MediaParts mediaParts = new MediaParts(media, configuration(settings).orElse(null), request);
        request.messages().forEach(message -> llmMessage(message, mediaParts).ifPresent(messages::add));

        Map<String, Object> additional = new LinkedHashMap<>(settings.extra());
        if (settings.reasoningEffort() != null) {
            additional.put("reasoning_effort", settings.reasoningEffort());
        }
        // -1 leaves temperature and output limit to the LLM config.
        return new LlmRequest(settings.model(), messages,
                request.tools().stream()
                        .map(tool -> ToolDefinition.of(tool.name(), tool.description(), tool.parametersSchema()))
                        .toList(),
                settings.temperature() == null ? -1 : settings.temperature(),
                settings.maxOutputTokens() == null ? -1 : settings.maxOutputTokens(),
                true, additional);
    }

    /** One history message as the model reads it; empty for a marker the model never sees. */
    private static Optional<LlmMessage> llmMessage(TurnMessage message, MediaParts mediaParts) {
        return switch (message.type()) {
            case USER -> Optional.of(LlmMessage.user(message.content().stream()
                    .map(part -> mediaParts.llmContent(part, message)).toList()));
            case ASSISTANT -> Optional.of(LlmMessage.assistant(message.text()));
            case TOOL_CALL -> Optional.of(LlmMessage.assistantWithToolCalls(
                    thinkingBlocks(message), toolCalls(message)));
            case TOOL_RESULT -> Optional.of(LlmMessage.tool(
                    String.valueOf(message.metadata().get("callId")), message.text()));
            case TOOL_DISPATCHED -> Optional.empty();
        };
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCall> toolCalls(TurnMessage message) {
        Map<String, String> signatures =
                (Map<String, String>) message.metadata().getOrDefault(THOUGHT_SIGNATURES, Map.of());
        return message.toolCalls().stream()
                .map(call -> new ToolCall(call.callId(), call.name(), call.arguments(),
                        signatures.get(call.callId())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<ThinkingBlock> thinkingBlocks(TurnMessage message) {
        List<Map<String, String>> stored =
                (List<Map<String, String>>) message.metadata().getOrDefault(THINKING_BLOCKS, List.of());
        return stored.stream()
                .map(block -> new ThinkingBlock(block.get("type"), block.get("text"),
                        block.get("data"), block.get("signature")))
                .toList();
    }

    // ── answer ──────────────────────────────────────────────────────────────

    /** One call's stream, put together chunk by chunk. */
    private final class StreamedAnswer {
        private final String requestId;
        private final TurnEventListener events;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, PartialToolCall> toolCalls = new TreeMap<>();
        private final Map<Integer, PartialThinkingBlock> thinking = new TreeMap<>();
        private Usage usage = Usage.ZERO;
        private boolean truncated;

        StreamedAnswer(String requestId, TurnEventListener events) {
            this.requestId = requestId;
            this.events = events;
        }

        void accept(LlmStreamChunk chunk) {
            switch (chunk) {
                case LlmStreamChunk.TextDelta delta -> {
                    text.append(delta.text());
                    events.onEvent(new TurnEvent.TextDelta(requestId, delta.text()));
                }
                case LlmStreamChunk.ThinkingDelta delta -> {
                    thinking.computeIfAbsent(delta.index(), index -> new PartialThinkingBlock()).feed(delta);
                    if (delta.textFragment() != null && !delta.textFragment().isEmpty()) {
                        events.onEvent(new TurnEvent.ReasoningDelta(requestId, delta.textFragment()));
                    }
                }
                case LlmStreamChunk.ToolCallDelta delta ->
                        toolCalls.computeIfAbsent(delta.index(), index -> new PartialToolCall()).feed(delta);
                case LlmStreamChunk.Done done -> {
                    usage = new Usage(done.inputTokens(), done.outputTokens());
                    truncated = done.finishReason() == FinishReason.LENGTH;
                }
            }
        }

        LlmAnswer complete() {
            List<ToolCall> calls = toolCalls.values().stream()
                    .map(PartialToolCall::build).flatMap(Optional::stream).toList();
            if (calls.isEmpty()) {
                return new LlmAnswer(List.of(TurnMessage.assistant(text.toString().trim())), usage, truncated);
            }
            TurnMessage message = TurnMessage.toolCalls(calls.stream()
                    .map(call -> new TurnMessage.ToolCall(call.id(), call.name(), call.arguments()))
                    .toList());
            Map<String, String> signatures = new HashMap<>();
            calls.stream().filter(call -> call.thoughtSignature() != null)
                    .forEach(call -> signatures.put(call.id(), call.thoughtSignature()));
            if (!signatures.isEmpty()) {
                message = message.with(THOUGHT_SIGNATURES, signatures);
            }
            List<Map<String, String>> replayable = thinking.values().stream()
                    .map(PartialThinkingBlock::replayable).flatMap(Optional::stream).toList();
            if (!replayable.isEmpty()) {
                message = message.with(THINKING_BLOCKS, replayable);
            }
            return new LlmAnswer(List.of(message), usage, truncated);
        }

        /** What a call that was told to stop had said — text only: a tool call cut off must not run. */
        LlmAnswer interrupted() {
            return text.isEmpty()
                    ? new LlmAnswer(List.of(), usage, false)
                    : new LlmAnswer(List.of(TurnMessage.assistant(text.toString().trim())), usage, false);
        }
    }

    private final class PartialToolCall {
        private String id;
        private String name;
        private String thoughtSignature;
        private final StringBuilder argumentsJson = new StringBuilder();

        void feed(LlmStreamChunk.ToolCallDelta delta) {
            if (delta.id() != null) id = delta.id();
            if (delta.name() != null) name = delta.name();
            if (delta.thoughtSignature() != null) thoughtSignature = delta.thoughtSignature();
            if (delta.argumentsFragment() != null) argumentsJson.append(delta.argumentsFragment());
        }

        /** Empty for a call that never got its id or name: there is nothing to run and nothing to answer. */
        Optional<ToolCall> build() {
            if (id == null || name == null) {
                log.warn("Discarding an incomplete tool call (id={}, name={})", id, name);
                return Optional.empty();
            }
            return Optional.of(new ToolCall(id, name, arguments(), thoughtSignature));
        }

        private Map<String, Object> arguments() {
            if (argumentsJson.isEmpty()) {
                return Map.of();
            }
            try {
                return json.readValue(argumentsJson.toString(), ARGUMENTS);
            } catch (JsonProcessingException e) {
                // The call still runs, with no arguments: the tool's error
                // tells the model what was missing, a dropped call would
                // leave it without any answer.
                log.warn("Tool call {} came with arguments that are no JSON object: {}", name, argumentsJson);
                return Map.of();
            }
        }
    }

    private static final class PartialThinkingBlock {
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

        /**
         * The block as stored for the next call — only one a provider takes
         * back, signed or encrypted. Plain reasoning text is never sent
         * again and would only fill the conversation.
         */
        Optional<Map<String, String>> replayable() {
            if (type == null || (signature == null && data == null)) {
                return Optional.empty();
            }
            Map<String, String> block = new HashMap<>();
            block.put("type", type);
            if (!text.isEmpty()) block.put("text", text.toString());
            if (data != null) block.put("data", data);
            if (signature != null) block.put("signature", signature);
            return Optional.of(block);
        }
    }
}
