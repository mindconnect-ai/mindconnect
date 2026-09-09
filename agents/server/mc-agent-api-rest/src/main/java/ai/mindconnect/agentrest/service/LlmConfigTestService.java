package ai.mindconnect.agentrest.service;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.in.LlmTranscription;
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
    private final ObjectProvider<LlmTranscription> transcriptionProvider;

    public LlmConfigTestService(RoutingLlmChatService chatService,
                                ObjectProvider<LlmEmbeddings> embeddingsProvider,
                                ObjectProvider<LlmTranscription> transcriptionProvider) {
        this.chatService = chatService;
        this.embeddingsProvider = embeddingsProvider;
        this.transcriptionProvider = transcriptionProvider;
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
        if (config.isSpeechToText()) {
            // Nothing to send as text — this type is tested with a recording.
            return Result.error("A speech-to-text config is tested by uploading a recording", 0);
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
     * Speech-to-text configs: the uploaded recording is transcribed and the
     * result shows the transcript, so an admin hears back whether endpoint,
     * model and key line up. A recording is what this test needs — there is
     * no text to send.
     *
     * @param filename    the upload's name; its extension tells the provider
     *                    which container the bytes are in
     * @param contentType the upload's media type, or {@code null}
     */
    public Result testTranscription(LlmConfig config, byte[] audio, String filename, String contentType) {
        long t0 = System.currentTimeMillis();
        LlmTranscription transcription = transcriptionProvider.getIfAvailable();
        if (transcription == null) {
            return Result.error("No transcription gateway configured in this application", 0);
        }
        if (audio == null || audio.length == 0) {
            return Result.error("The upload was empty", 0);
        }
        try {
            // By name, like the chat test — so the test follows an alias exactly
            // as production traffic does.
            TranscriptionResult result = transcription.transcribe(config.name(),
                    TranscriptionRequest.of(audio, filename, contentType));
            long durMs = System.currentTimeMillis() - t0;
            log.info("Transcription test '{}' OK in {} ms ({} characters)",
                    config.name(), durMs, result.text().length());
            String text = result.text().isBlank() ? "(silence — the model heard nothing)" : result.text();
            return Result.ok(text, result.inputTokens(), result.outputTokens(), durMs,
                    describeAudio(filename, result));
        } catch (Exception e) {
            long durMs = System.currentTimeMillis() - t0;
            String detail = describeCause(e);
            log.warn("Transcription test '{}' failed after {} ms: {}", config.name(), durMs, detail, e);
            return Result.error(detail, durMs);
        }
    }

    /** The meta line of a transcription test: file, detected language, length. */
    private static String describeAudio(String filename, TranscriptionResult result) {
        StringBuilder sb = new StringBuilder(filename);
        if (result.language() != null) sb.append(" · ").append(result.language());
        if (result.durationSeconds() != null) {
            sb.append(String.format(java.util.Locale.ROOT, " · %.1f s", result.durationSeconds()));
        }
        return sb.toString();
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
