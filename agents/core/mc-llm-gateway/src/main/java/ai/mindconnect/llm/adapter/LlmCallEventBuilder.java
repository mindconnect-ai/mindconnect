package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.port.in.LlmCallListener;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what one provider call tells its {@link LlmCallListener}: when it
 * started, the request as sent, every SSE block as it arrived, and at the
 * end the outcome. {@link #report} hands the {@link LlmCallEvent} over inside
 * a try/catch, because a broken trace must never tear down the chat.
 */
public final class LlmCallEventBuilder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallEventBuilder.class);

    private final Instant startedAt;
    private final long start;
    private final LlmConfig config;
    private final LlmRequest request;
    private final List<String> responseEvents = new ArrayList<>();
    private String requestJson;

    /** @param config the resolved config the call goes to; its model name is what the event carries */
    public LlmCallEventBuilder(LlmConfig config, LlmRequest request) {
        this.start = System.currentTimeMillis();
        this.startedAt = Instant.ofEpochMilli(start);
        this.config = config;
        this.request = request;
    }

    /**
     * The request as the trace carries it: pretty-printed, media payloads cut
     * out. A failure to serialise leaves the trace without its request; it
     * must not leave the call without its trace.
     */
    public void request(ObjectWriter prettyWriter, ObjectNode requestNode) {
        try {
            requestJson = prettyWriter.writeValueAsString(TraceRedaction.redactMedia(requestNode));
        } catch (Exception e) {
            // The wire request is built from the same node and fails on its own; the trace just goes without.
        }
    }

    /** The SSE blocks as they arrived; the gateway adds each one. */
    public List<String> responseEvents() {
        return responseEvents;
    }

    /** Milliseconds since the call started. */
    public long elapsedMillis() {
        return System.currentTimeMillis() - start;
    }

    /** Hands the event to the listener; a listener that throws is logged and otherwise ignored. */
    public void report(LlmCallListener listener, LlmCallEvent.ResponseSummary response,
                       int promptTokens, int completionTokens, String finishReason,
                       Integer errorStatus, String errorBody) {
        if (listener == null) return;
        try {
            listener.onCall(new LlmCallEvent(
                    startedAt, elapsedMillis(),
                    request.configName(), config.model(),
                    promptTokens, completionTokens, finishReason,
                    requestJson, responseEvents, response,
                    errorStatus, errorBody));
        } catch (Exception e) {
            log.warn("LlmCallListener threw — swallowing to keep chat alive: {}", e.getMessage());
        }
    }

    /** What the model said, or {@code null} when it said nothing and called nothing. */
    public static LlmCallEvent.ResponseSummary summary(CharSequence text, List<ToolCall> toolCalls) {
        String assistantText = text.length() == 0 ? null : text.toString();
        if (assistantText == null && toolCalls.isEmpty()) return null;
        return new LlmCallEvent.ResponseSummary(assistantText, toolCalls);
    }
}
