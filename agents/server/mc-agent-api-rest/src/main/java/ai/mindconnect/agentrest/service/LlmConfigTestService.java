package ai.mindconnect.agentrest.service;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends a one-shot user message to a configured LLM and returns the
 * response. Used by the LLM-config detail page's "Test" button so an
 * admin can verify that an API key + model are wired correctly without
 * having to spin up a real agent session.
 *
 * <p>Routes through {@link RoutingLlmChatService} so the test exercises
 * exactly the same path as production traffic — including alias
 * resolution ({@code delegatesTo}) — by config name. The streamed chunks
 * are accumulated into a single reply for the dialog.
 */
@Service
public class LlmConfigTestService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigTestService.class);

    private final RoutingLlmChatService chatService;
    private final ObjectProvider<LlmEmbeddings> embeddingsProvider;

    public LlmConfigTestService(RoutingLlmChatService chatService,
                                ObjectProvider<LlmEmbeddings> embeddingsProvider) {
        this.chatService = chatService;
        this.embeddingsProvider = embeddingsProvider;
    }

    /**
     * Sends {@code message} as a single user-turn to the config (resolved
     * by name through the routing service, so aliases are followed) and
     * returns the full assistant reply. Returns an error result on any
     * provider failure so the caller can render it in the dialog.
     */
    public Result test(LlmConfig config, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be empty");
        }
        if (config.isEmbedding()) {
            return testEmbedding(config, message);
        }
        long t0 = System.currentTimeMillis();
        LlmRequest req = LlmRequest.streaming(config.name(), List.of(LlmMessage.user(message)));
        StringBuilder text = new StringBuilder();
        var done = new LlmStreamChunk.Done[1];
        try {
            chatService.chatStreaming(req, chunk -> {
                if (chunk instanceof LlmStreamChunk.TextDelta td) {
                    text.append(td.text());
                } else if (chunk instanceof LlmStreamChunk.Done d) {
                    done[0] = d;
                }
            });
            long durMs = System.currentTimeMillis() - t0;
            int inTokens = done[0] != null ? done[0].inputTokens() : 0;
            int outTokens = done[0] != null ? done[0].outputTokens() : 0;
            String finish = done[0] != null && done[0].finishReason() != null
                    ? done[0].finishReason().name() : "";
            log.info("LLM test '{}' OK in {} ms ({} in / {} out tokens)",
                    config.name(), durMs, inTokens, outTokens);
            return Result.ok(text.toString(), inTokens, outTokens, durMs, finish);
        } catch (Exception e) {
            long durMs = System.currentTimeMillis() - t0;
            String detail = describeCause(e);
            // Full stack at WARN so the underlying I/O error is diagnosable; the
            // dialog/Result gets the unwrapped root cause instead of the generic
            // "Failed to stream from OpenAI-compatible endpoint" wrapper.
            log.warn("LLM test '{}' failed after {} ms: {}", config.name(), durMs, detail, e);
            return Result.error(detail, durMs);
        }
    }

    /**
     * Embedding configs: instead of a chat turn, the text is embedded and the
     * result shows the vector's dimension plus its first values — enough to
     * see that endpoint, model and key line up.
     */
    private Result testEmbedding(LlmConfig config, String message) {
        long t0 = System.currentTimeMillis();
        LlmEmbeddings embeddings = embeddingsProvider.getIfAvailable();
        if (embeddings == null) {
            return Result.error("No embeddings gateway configured in this application", 0);
        }
        try {
            float[] vector = embeddings.embed(config, List.of(message)).get(0);
            long durMs = System.currentTimeMillis() - t0;
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < Math.min(8, vector.length); i++) {
                if (i > 0) preview.append(", ");
                preview.append(String.format(java.util.Locale.ROOT, "%.4f", vector[i]));
            }
            log.info("Embedding test '{}' OK in {} ms (dimension {})",
                    config.name(), durMs, vector.length);
            return Result.ok("Vector dimension " + vector.length + " — ["
                    + preview + (vector.length > 8 ? ", …" : "") + "]",
                    0, 0, durMs, "embedding");
        } catch (Exception e) {
            long durMs = System.currentTimeMillis() - t0;
            String detail = describeCause(e);
            log.warn("Embedding test '{}' failed after {} ms: {}", config.name(), durMs, detail, e);
            return Result.error(detail, durMs);
        }
    }

    /**
     * Renders the exception plus its deepest cause. Provider adapters wrap the
     * real failure (e.g. {@code java.net.ConnectException: Connection refused},
     * an HTTP 401/404 body) in a generic {@link RuntimeException}; without
     * unwrapping, the dialog would only show the opaque wrapper message.
     */
    private static String describeCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String head = e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "");
        if (root == e) return head;
        return head + " (cause: " + root.getClass().getSimpleName()
                + (root.getMessage() != null ? ": " + root.getMessage() : "") + ")";
    }

    /**
     * Either a successful response (text + token counts + duration) or a
     * failed one (error description + duration). The UI renders both
     * shapes via the same dialog template; consumers pattern-match on
     * {@link #ok}.
     */
    public record Result(
            boolean ok,
            String text,
            String errorMessage,
            int inputTokens,
            int outputTokens,
            long durationMs,
            String finishReason
    ) {
        public static Result ok(String text, int in, int out, long ms, String finish) {
            return new Result(true, text, null, in, out, ms, finish);
        }

        public static Result error(String errorMessage, long ms) {
            return new Result(false, null, errorMessage, 0, 0, ms, "");
        }
    }
}
