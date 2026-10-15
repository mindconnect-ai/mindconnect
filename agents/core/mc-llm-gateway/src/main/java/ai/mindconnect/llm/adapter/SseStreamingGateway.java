package ai.mindconnect.llm.adapter;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.Consumer;

/**
 * The call every streaming provider makes the same way: resolve the config,
 * send one JSON request, read the answer as server-sent events, book what
 * each block contributed, tell the listener how it went, and end with one
 * {@link LlmStreamChunk.Done}. A provider adds its request body, its HTTP
 * request and how one block is parsed; nothing else differs on the wire.
 *
 * <p>A transient provider error (rate limit, overload) leaves as an
 * {@link ai.mindconnect.llm.domain.LlmTransientException} before anything
 * streamed, which is what lets the retry decorator try again safely.
 */
public abstract class SseStreamingGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SseStreamingGateway.class);

    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final ObjectWriter prettyWriter;
    private final EncryptionHelper encryption;
    private final EnvVarResolver env;
    private final String provider;

    /**
     * @param provider the provider's name in logs and error messages
     * @param env      where {@code ${VAR}} placeholders in a config resolve from
     */
    protected SseStreamingGateway(String provider, OkHttpClient httpClient, ObjectMapper objectMapper,
                                  EncryptionHelper encryption, EnvVarResolver env) {
        this.provider = provider;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
        this.encryption = encryption;
        this.env = env;
    }

    /**
     * What one call accumulates while its stream arrives: the provider's
     * tool-call builders and parse state. One per call, since a gateway
     * serves many calls at once.
     */
    protected abstract static class Stream {

        /** What one SSE block contributed; the chunks go to the handler on the way. */
        protected abstract StreamDelta parse(String block, Consumer<LlmStreamChunk> handler);

        /** What was held back until the stream ended; nothing, for most providers. */
        protected StreamDelta flush(Consumer<LlmStreamChunk> handler) {
            return StreamDelta.NONE;
        }

        /** The complete tool calls, for the trace. */
        protected abstract List<ToolCall> toolCalls();
    }

    protected abstract Stream newStream();

    /** The request body, with the config already resolved. */
    protected abstract ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException;

    protected abstract Request httpRequest(LlmConfig config, String body);

    /**
     * Executes the call. A provider that answers a refused field with a
     * second attempt replaces {@code call[0]}, so the cancellation hook
     * reaches the attempt in flight.
     */
    protected Response open(Call[] call, LlmConfig config, ObjectNode requestNode) throws IOException {
        return call[0].execute();
    }

    @Override
    public final void chatStreaming(LlmConfig config, LlmRequest request,
                                    Consumer<LlmStreamChunk> handler,
                                    Cancellation cancellation,
                                    LlmCallListener listener) {
        config = config.resolved(env, encryption);
        log.debug("{} stream → model={} messages={} tools={}", provider, config.model(),
                request.messages() == null ? 0 : request.messages().size(),
                request.tools() == null ? 0 : request.tools().size());
        LlmCallEventBuilder trace = new LlmCallEventBuilder(config, request);
        Stream stream = newStream();
        StringBuilder assistantText = new StringBuilder();
        Integer errorStatus = null;
        String errorBody = null;

        ObjectNode requestNode;
        String body;
        try {
            requestNode = buildRequestNode(config, request);
            trace.request(prettyWriter, requestNode);
            LlmWireLog.request(prettyWriter, requestNode);
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            trace.report(listener, null, 0, 0, null, null, e.getMessage());
            throw new RuntimeException("Failed to build " + provider + " request", e);
        }

        Call[] call = { httpClient.newCall(httpRequest(config, body)) };
        cancellation.registerAbort(() -> call[0].cancel());

        int textTokenCount = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        FinishReason finish = FinishReason.STOP;
        StringBuilder debugAccumulatedText = LlmWireLog.enabled() ? new StringBuilder() : null;
        boolean cancelledClean = false;

        try (Response response = open(call, config, requestNode)) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw LlmHttpError.failure(log, provider, config, errorStatus,
                        response.header("retry-after"), errorBody);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                SseBlockReader blocks = new SseBlockReader(reader, cancellation);
                String block;
                StreamDelta d = StreamDelta.NONE;
                while (!d.endOfStream() && (block = blocks.next()) != null) {
                    trace.responseEvents().add(block);
                    d = stream.parse(block, handler);
                    textTokenCount += d.textDeltas();
                    if (d.text() != null) {
                        assistantText.append(d.text());
                        if (debugAccumulatedText != null) debugAccumulatedText.append(d.text());
                    }
                    if (d.finishReason() != null) finish = d.finishReason();
                    if (d.inputTokens() > 0) inputTokens = d.inputTokens();
                    if (d.outputTokens() > 0) outputTokens = d.outputTokens();
                }
                cancelledClean = blocks.cancelled();
                if (blocks.trailing() != null) trace.responseEvents().add(blocks.trailing());
                // After a cancel nothing more may reach the handler.
                if (!cancelledClean) {
                    StreamDelta tail = stream.flush(handler);
                    textTokenCount += tail.textDeltas();
                    if (tail.text() != null) {
                        assistantText.append(tail.text());
                        if (debugAccumulatedText != null) debugAccumulatedText.append(tail.text());
                    }
                }
            }
        } catch (IOException e) {
            if (call[0].isCanceled() || cancellation.isCancelled()) {
                cancelledClean = true;
            } else {
                trace.report(listener, LlmCallEventBuilder.summary(assistantText, stream.toolCalls()),
                        inputTokens, outputTokens, finish.name(), errorStatus,
                        errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from " + provider, e);
            }
        } catch (RuntimeException e) {
            trace.report(listener, LlmCallEventBuilder.summary(assistantText, stream.toolCalls()),
                    inputTokens, outputTokens, finish.name(), errorStatus, errorBody);
            throw e;
        }

        trace.report(listener, LlmCallEventBuilder.summary(assistantText, stream.toolCalls()),
                inputTokens, outputTokens, finish.name(), null, null);
        if (cancelledClean) {
            log.debug("{} stream cancelled by caller after {}ms", provider, trace.elapsedMillis());
            return;
        }
        if (debugAccumulatedText != null && debugAccumulatedText.length() > 0) {
            LlmWireLog.response(textTokenCount, debugAccumulatedText);
        }
        log.debug("{} stream ← finish={} text-tokens={} in={}t out={}t {}ms",
                provider, finish, textTokenCount, inputTokens, outputTokens, trace.elapsedMillis());
        handler.accept(new LlmStreamChunk.Done(finish, inputTokens, outputTokens));
    }
}
