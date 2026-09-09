package ai.mindconnect.agentrest.service;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transcription as a job: the recording is stored, a task is queued, and the
 * caller gets an id to come back with.
 *
 * <p>Why a task and not a straight call: a long recording takes longer than a
 * caller wants to hold a connection open, the provider does fail now and
 * then, and the queue already answers both — it retries, it survives a
 * restart with the Postgres store, and it spreads over nodes. What a caller
 * gets back is a place to poll and a channel to watch; both are handed over
 * with the job, so nobody has to assemble a URL.
 *
 * <p>The audio does not travel in the task's payload. Payloads are documents,
 * and a recording is megabytes — it goes to the {@link FileStore} and the
 * payload names it. That is also what makes a second attempt possible: it
 * reads the same file.
 *
 * <p>The recording stays in the store afterwards, and its id comes back with
 * the job. It is an upload like any other from there on: fetch it again, keep
 * it as the evidence behind a transcript, delete it when it has served its
 * purpose. Nothing here throws away what a caller may still want.
 */
@Service
public class TranscriptionJobService {

    /** The task type this service registers a worker for. */
    public static final String TYPE = "transcription";

    /**
     * The LLM config a job uses when the request names none. A name, not a
     * model: an alias of this name decides what actually serves.
     */
    public static final String DEFAULT_CONFIG_NAME = "speech-to-text";

    private static final Logger log = LoggerFactory.getLogger(TranscriptionJobService.class);

    /**
     * Attempts per job. A retry is a paid provider call, so this is low on
     * purpose: it covers a hiccup, not an outage.
     */
    private static final int MAX_ATTEMPTS = 3;

    private final TaskQueue queue;
    private final FileStore fileStore;
    private final ObjectProvider<LlmTranscription> transcription;
    private final TranscriptionChannels channels;
    private final ObjectMapper objectMapper;

    public TranscriptionJobService(TaskQueue queue, FileStore fileStore,
                                   ObjectProvider<LlmTranscription> transcription,
                                   TranscriptionChannels channels, ObjectMapper objectMapper) {
        this.queue = queue;
        this.fileStore = fileStore;
        this.transcription = transcription;
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    /** Teaches the queue what a transcription task is. Idempotent per queue. */
    @PostConstruct
    void registerWorker() {
        if (queue.hasRegisteredType(TYPE)) return;
        queue.register(TYPE, this::run);
    }

    /**
     * Stores the recording and queues its transcription.
     *
     * @param configName the LLM config to transcribe with, or {@code null} for
     *                   the default name — an alias is followed
     * @param userId     who asked, when the request carried a principal;
     *                   {@code null} on an installation without login
     * @return the queued task's id
     */
    public Job submit(byte[] audio, String filename, String contentType,
                      String configName, String language, String prompt, String userId)
            throws IOException {
        StoredFile stored;
        try (InputStream content = new java.io.ByteArrayInputStream(audio)) {
            stored = fileStore.save(filename, contentType, content);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", stored.id());
        payload.put("filename", filename);
        if (contentType != null) payload.put("contentType", contentType);
        if (configName != null) payload.put("configName", configName);
        if (language != null) payload.put("language", language);
        if (prompt != null) payload.put("prompt", prompt);
        if (userId != null) payload.put("userId", userId);

        // The id is ours before the queue has it, so "queued" is on the
        // channel before a worker can publish "running". Submitting first and
        // announcing afterwards is a race the worker sometimes wins, and the
        // events would then arrive out of order.
        String taskId = "task_" + UUID.randomUUID();
        channels.publish(taskId, TranscriptionEvent.status("queued"));
        queue.submit(TaskSubmission.of(TYPE, payload).withId(taskId).withMaxAttempts(MAX_ATTEMPTS));

        log.info("Transcription job {} queued ({} bytes, file {})", taskId, audio.length, stored.id());
        return new Job(taskId, stored.id());
    }

    /** A queued job: the task to follow, and the recording it reads. */
    public record Job(String taskId, String fileId) { }

    /** The job as it stands, or empty when no task has that id. */
    public Optional<TaskRecord> find(String taskId) {
        return queue.get(taskId);
    }

    /**
     * Waits for the job to end, at most {@code timeout}. Returns the record as
     * it looks when the wait is over — still running is a valid answer.
     */
    public Optional<TaskRecord> await(String taskId, Duration timeout) {
        if (queue.get(taskId).isEmpty()) return Optional.empty();
        return Optional.ofNullable(queue.await(taskId, timeout));
    }

    /** The transcript of a completed job, parsed back out of the task's result. */
    public Optional<TranscriptionResult> resultOf(TaskRecord task) {
        if (task.status() != TaskStatus.COMPLETED || task.result() == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(task.result(), TranscriptionResult.class));
        } catch (Exception e) {
            log.warn("Transcription job {} has an unreadable result: {}", task.id(), e.toString());
            return Optional.empty();
        }
    }

    /** The recording a job was given, as stored in the file store. */
    public static Optional<String> recordingOf(TaskRecord task) {
        Object fileId = task.payload().get("fileId");
        return fileId == null ? Optional.empty() : Optional.of(String.valueOf(fileId));
    }

    /** Who asked for this job, when the request carried a principal. */
    public static Optional<String> requesterOf(TaskRecord task) {
        Object userId = task.payload().get("userId");
        return userId == null ? Optional.empty() : Optional.of(String.valueOf(userId));
    }

    // ── The worker ─────────────────────────────────────────────────────────

    /**
     * One attempt at a job. Whatever happens is published on the job's channel
     * before it reaches the queue, so a watcher learns of it without asking —
     * including the failure, which the queue would otherwise only record.
     */
    private TaskOutcome run(TaskContext ctx) throws Exception {
        String taskId = ctx.task().id();
        try {
            return transcribe(ctx);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            channels.publish(taskId, TranscriptionEvent.failed(reason));
            log.warn("Transcription job {} failed on attempt {} of {}: {}",
                    taskId, ctx.task().attempt(), ctx.task().maxAttempts(), reason);
            throw e;
        }
    }

    /**
     * Reads the stored recording and transcribes it; the transcript becomes
     * the task's result, as JSON, so language and duration survive with it.
     */
    private TaskOutcome transcribe(TaskContext ctx) throws Exception {
        String taskId = ctx.task().id();
        Map<String, Object> payload = ctx.task().payload();
        String fileId = string(payload, "fileId");
        channels.publish(taskId, TranscriptionEvent.status("running"));

        LlmTranscription speech = transcription.getIfAvailable();
        if (speech == null) {
            throw new IllegalStateException("This server has no speech-to-text gateway");
        }
        if (fileStore.find(fileId).isEmpty()) {
            throw new IllegalStateException("The recording is gone from the file store: " + fileId);
        }

        byte[] audio;
        try (InputStream content = fileStore.content(fileId)) {
            audio = content.readAllBytes();
        }
        TranscriptionRequest request = new TranscriptionRequest(audio,
                string(payload, "filename"), string(payload, "contentType"),
                string(payload, "language"), string(payload, "prompt"));

        String configName = payload.get("configName") == null
                ? DEFAULT_CONFIG_NAME : string(payload, "configName");
        // The transcript goes onto the channel as it forms, so a watching
        // client reads along instead of waiting for the end. A model that
        // answers in one piece publishes one delta — same events, same code
        // on the other side.
        TranscriptionResult result = speech.transcribe(configName, request,
                fragment -> channels.publish(taskId, TranscriptionEvent.delta(fragment)));

        channels.publish(taskId, TranscriptionEvent.completed(result.text(), result.language()));
        log.info("Transcription job {} done ({} characters)", taskId,
                result.text() == null ? 0 : result.text().length());
        return TaskOutcome.done(objectMapper.writeValueAsString(result));
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
