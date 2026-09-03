package ai.mindconnect.agent.domain.view;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.domain.TraceContext;

import java.time.Instant;

/**
 * An LLM call as a list shows it: who called what, when, how long, how many
 * tokens, how it ended — without the request and response payloads that
 * make a full {@link LlmCallTrace} hundreds of kilobytes. The trace itself
 * is a header; a header is not a trace.
 */
public interface LlmCallTraceHeader {

    java.util.UUID id();

    TraceContext context();

    Instant startedAt();

    long durationMs();

    String llmConfigName();

    String modelName();

    int promptTokens();

    int completionTokens();

    String finishReason();

    Integer errorStatus();
}
