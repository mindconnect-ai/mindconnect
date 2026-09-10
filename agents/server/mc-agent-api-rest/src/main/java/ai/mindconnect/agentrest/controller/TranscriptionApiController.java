package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agentrest.dto.TranscriptionJobResponse;
import ai.mindconnect.agentrest.dto.TranscriptionJobResult;
import ai.mindconnect.agentrest.service.TranscriptionChannels;
import ai.mindconnect.agentrest.service.TranscriptionEvent;
import ai.mindconnect.agentrest.service.TranscriptionJobService;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;

/**
 * Turning a recording into text, as a job.
 *
 * <p>Three moves: post the audio and get an id, poll the id for the
 * transcript, or attach to the job's stream and be told. The submit answer
 * carries both URLs, so a client picks whichever fits without building a URL
 * itself.
 *
 * <p>Why a job and not a straight answer: a long recording outlasts a
 * comfortable request, providers do fail now and then, and the queue behind
 * this already retries and survives a restart. The shape mirrors what
 * transcription services do for the same reason.
 */
@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionApiController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionApiController.class);

    /** How long an events connection stays open before the client reattaches. */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    /** Upper bound for the polling endpoint's optional wait. */
    private static final int MAX_WAIT_SECONDS = 60;

    private final TranscriptionJobService jobs;
    private final TranscriptionChannels channels;
    private final ObjectMapper objectMapper;

    public TranscriptionApiController(TranscriptionJobService jobs, TranscriptionChannels channels,
                                      ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    @Operation(tags = "Transcriptions", summary = "Queue a recording for transcription",
            description = "Multipart upload of an audio file (WebM, WAV, MP3, M4A, OGG, FLAC). "
                    + "Answers 202 with the job id plus the URL to poll and the URL to stream. "
                    + "'model' names an LLM config of type SPEECH_TO_TEXT — an alias of that name "
                    + "is followed; omitted, the config named 'speech-to-text' serves.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscriptionJobResponse> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "prompt", required = false) String prompt,
            Principal user) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String filename = file.getOriginalFilename() == null ? "recording.webm" : file.getOriginalFilename();
        TranscriptionJobService.Job job = jobs.submit(file.getBytes(), filename, file.getContentType(),
                model, language, prompt, user == null ? null : user.getName());

        log.info("POST /api/transcriptions file=\"{}\" ({} bytes) → job {}, recording {}",
                filename, file.getSize(), job.taskId(), job.fileId());
        return ResponseEntity.accepted().body(new TranscriptionJobResponse(
                job.taskId(), "queued",
                "/api/transcriptions/" + job.taskId(),
                "/api/transcriptions/" + job.taskId() + "/events",
                TranscriptionChannels.channelId(job.taskId()),
                job.fileId(), fileUrl(job.fileId())));
    }

    @Operation(tags = "Transcriptions", summary = "Fetch a transcription job",
            description = "The job's status, and its transcript once it is done. 'wait' holds the "
                    + "request for up to that many seconds (max 60) until the job ends — a caller "
                    + "who wants the synchronous feel without polling.")
    @GetMapping("/{taskId}")
    public ResponseEntity<TranscriptionJobResult> result(
            @PathVariable String taskId,
            @RequestParam(value = "wait", required = false) Integer wait,
            Principal user) {
        Optional<TaskRecord> found = wait == null || wait <= 0
                ? jobs.find(taskId)
                : jobs.await(taskId, Duration.ofSeconds(Math.min(wait, MAX_WAIT_SECONDS)));
        if (found.isEmpty()) return ResponseEntity.notFound().build();

        TaskRecord task = found.get();
        if (!mayRead(task, user)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(describe(task));
    }

    @Operation(tags = "Transcriptions", summary = "Stream a transcription job's events (SSE)",
            description = "Server-Sent Events from the job's channel: queued, running, and the "
                    + "transcript on completion. The stream starts with the job's state as it is "
                    + "now, so a client that attaches late is not left waiting, and closes when "
                    + "the job ends. 'afterSeq' resumes a dropped connection without a gap.")
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String taskId,
            @RequestParam(value = "afterSeq", required = false) Long afterSeq,
            Principal user) {
        Optional<TaskRecord> found = jobs.find(taskId);
        if (found.isEmpty() || !mayRead(found.get(), user)) {
            return ResponseEntity.notFound().build();
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // The task is the truth, the channel is the delivery: a job that ended
        // before anyone attached is answered from the record, and the stream
        // closes right away instead of waiting for events that will not come.
        TaskRecord task = found.get();
        if (terminal(task.status())) {
            send(emitter, fromRecord(task));
            emitter.complete();
            return ResponseEntity.ok(emitter);
        }

        Subscription subscription = channels.subscribe(taskId,
                afterSeq == null ? 0 : afterSeq,
                event -> {
                    send(emitter, event.value());
                    if (terminal(event.value().status())) emitter.complete();
                });
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(e -> close(subscription));
        return ResponseEntity.ok(emitter);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * A job recorded a requester when the request carried one. Then only that
     * requester reads it — an id is a name, not a permission. A job submitted
     * without a principal (an installation with no login) is readable by
     * whoever can reach the endpoint, like the rest of this API.
     */
    private static boolean mayRead(TaskRecord task, Principal user) {
        Optional<String> requester = TranscriptionJobService.requesterOf(task);
        if (requester.isEmpty()) return true;
        return user != null && requester.get().equals(user.getName());
    }

    private TranscriptionJobResult describe(TaskRecord task) {
        TranscriptionResult result = jobs.resultOf(task).orElse(null);
        return new TranscriptionJobResult(
                task.id(),
                task.status().name().toLowerCase(java.util.Locale.ROOT),
                result == null ? null : result.text(),
                result == null ? null : result.language(),
                result == null ? null : result.durationSeconds(),
                result == null ? 0 : result.inputTokens(),
                result == null ? 0 : result.outputTokens(),
                task.failure() == null ? null : task.failure().message(),
                TranscriptionJobService.recordingOf(task).orElse(null),
                TranscriptionJobService.recordingOf(task).map(TranscriptionApiController::fileUrl)
                        .orElse(null),
                task.submittedAt(),
                task.endedAt());
    }

    /** Where a stored recording is fetched, and deleted. */
    private static String fileUrl(String fileId) {
        return fileId == null ? null : "/api/files/" + fileId;
    }

    /** The job's current state as one event, for a client that attached late. */
    private TranscriptionEvent fromRecord(TaskRecord task) {
        String status = task.status().name().toLowerCase(java.util.Locale.ROOT);
        if (task.status() == TaskStatus.COMPLETED) {
            TranscriptionResult result = jobs.resultOf(task).orElse(null);
            return result == null ? TranscriptionEvent.status(status)
                    : TranscriptionEvent.completed(result.text(), result.language());
        }
        if (task.status() == TaskStatus.FAILED) {
            return TranscriptionEvent.failed(
                    task.failure() == null ? "The job failed" : task.failure().message());
        }
        return TranscriptionEvent.status(status);
    }

    private static boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private static boolean terminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private void send(SseEmitter emitter, TranscriptionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.status())
                    .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // The client went away, or the connection broke. Nothing to
            // salvage here: the job carries on and its result stays fetchable.
            emitter.completeWithError(e);
        }
    }

    private static void close(Subscription subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // closing twice is normal — completion and timeout can both fire
        }
    }
}
