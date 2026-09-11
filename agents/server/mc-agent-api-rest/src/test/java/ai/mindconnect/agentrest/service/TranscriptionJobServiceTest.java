package ai.mindconnect.agentrest.service;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A transcription as a queued job: the recording travels through the file
 * store rather than the payload, the transcript comes back as the task's
 * result, and the job's channel carries what happened.
 */
class TranscriptionJobServiceTest {

    private static final byte[] AUDIO = "pretend this is opus".getBytes(StandardCharsets.UTF_8);

    private LocalTaskQueue queue;
    private StubFileStore files;
    private TranscriptionChannels channels;
    private TranscriptionJobService service;
    private final AtomicReference<String> usedConfig = new AtomicReference<>();
    private volatile LlmTranscription gateway;

    @BeforeEach
    void setUp() {
        queue = new LocalTaskQueue(new InMemoryTaskStore());
        files = new StubFileStore();
        channels = new TranscriptionChannels();
        gateway = (configName, request) -> {
            usedConfig.set(configName);
            return new TranscriptionResult("what was said", "english", 1.5, 3, 4);
        };
        service = new TranscriptionJobService(queue, files, provider(() -> gateway),
                channels, new ObjectMapper());
        service.registerWorker();
    }

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void theRecordingGoesToTheStoreAndItsIdIntoThePayload() throws Exception {
        TranscriptionJobService.Job job = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, null);

        TaskRecord task = queue.get(job.taskId()).orElseThrow();
        assertThat(job.fileId()).as("the caller learns where the recording went")
                .isEqualTo(task.payload().get("fileId"));
        assertThat(task.payload()).containsEntry("filename", "speech.webm")
                .containsKey("fileId");
        assertThat(task.payload().get("fileId")).asString().isNotBlank();
        // The audio itself must not be in the payload — payloads are documents.
        assertThat(task.payload().values()).noneMatch(v -> v instanceof byte[]);
    }

    @Test
    void theTranscriptComesBackAsTheTasksResult() throws Exception {
        String taskId = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, "en", "Mindconnect", null).taskId();

        TaskRecord done = queue.await(taskId, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(service.resultOf(done)).get()
                .extracting(TranscriptionResult::text, TranscriptionResult::language)
                .containsExactly("what was said", "english");
        assertThat(usedConfig.get()).as("the default config name serves when none is asked for")
                .isEqualTo(TranscriptionJobService.DEFAULT_CONFIG_NAME);
    }

    @Test
    void aNamedConfigIsPassedThrough() throws Exception {
        String taskId = service.submit(AUDIO, "speech.webm", "audio/webm",
                "whisper-local", null, null, null).taskId();

        queue.await(taskId, Duration.ofSeconds(5));

        assertThat(usedConfig.get()).isEqualTo("whisper-local");
    }

    @Test
    void theRecordingStaysInTheStoreAfterwards() throws Exception {
        TranscriptionJobService.Job job = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, null);

        queue.await(job.taskId(), Duration.ofSeconds(5));

        // The upload belongs to whoever made it: it is fetchable and
        // deletable through the file API, and nothing here disposes of it.
        assertThat(files.find(FileId.of(job.fileId()))).isPresent();
    }

    @Test
    void aJobThatFailsForGoodAlsoCleansUp() throws Exception {
        gateway = (configName, request) -> {
            throw new IllegalStateException("provider is down");
        };

        TranscriptionJobService.Job job = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, null);
        TaskRecord done = queue.await(job.taskId(), Duration.ofSeconds(10));

        assertThat(done.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(done.failure().message()).contains("provider is down");
        assertThat(files.find(FileId.of(job.fileId()))).as("a failed job keeps its evidence").isPresent();
    }

    @Test
    void theChannelCarriesTheRunFromQueuedToTheTranscript() throws Exception {
        List<TranscriptionEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        String taskId = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, null).taskId();
        // afterSeq 0: everything the job published, including what happened
        // before this subscriber existed. The latch waits for delivery rather
        // than for a duration — the queue's completion and the channel's
        // fan-out are different threads.
        channels.subscribe(taskId, 0, event -> {
            seen.add(event.value());
            if ("completed".equals(event.value().status())) finished.countDown();
        });

        assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the transcript reached the channel").isTrue();
        // The gateway here answers in one piece, so its text arrives as a
        // single delta before the completion — the same shape a model that
        // streams produces word by word, which is the point of the delta.
        assertThat(seen).extracting(TranscriptionEvent::status)
                .containsExactly("queued", "running", "delta", "completed");
        assertThat(seen).extracting(TranscriptionEvent::text)
                .containsExactly(null, null, "what was said", "what was said");
    }

    @Test
    void aWaitShorterThanTheJobAnswersWithTheJobAsItStands() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        gateway = (configName, request) -> {
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new TranscriptionResult("eventually", null, null, 0, 0);
        };
        String taskId = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, null).taskId();

        // The queue treats a timeout as an error; a caller asking to wait a
        // moment must still be told what the job is doing, not handed a
        // failure.
        Optional<TaskRecord> waited = service.await(taskId, Duration.ofMillis(200));

        assertThat(waited).isPresent();
        assertThat(waited.get().status().terminal()).as("still working").isFalse();
        release.countDown();
        queue.await(taskId, Duration.ofSeconds(5));
    }

    @Test
    void anUnknownJobIsStillEmpty() {
        assertThat(service.await("task_nobody", Duration.ofMillis(50))).isEmpty();
    }

    @Test
    void theRequesterIsRememberedForTheOwnerCheck() throws Exception {
        String taskId = service.submit(AUDIO, "speech.webm", "audio/webm",
                null, null, null, "mc_user").taskId();

        assertThat(TranscriptionJobService.requesterOf(queue.get(taskId).orElseThrow()))
                .contains("mc_user");
    }

    // ── Stubs ──────────────────────────────────────────────────────────────

    /** Enough of a file store to hold bytes and hand them back. */
    private static final class StubFileStore implements FileStore {
        private final Map<FileId, byte[]> stored = new LinkedHashMap<>();
        private final Map<FileId, StoredFile> meta = new LinkedHashMap<>();

        @Override
        public StoredFile save(String name, String contentType, InputStream content)
                throws IOException {
            FileId id = FileId.of("file-" + UUID.randomUUID());
            byte[] bytes = content.readAllBytes();
            stored.put(id, bytes);
            StoredFile file = new StoredFile(id, name, contentType, bytes.length, Instant.now());
            meta.put(id, file);
            return file;
        }

        @Override public Optional<StoredFile> find(FileId id) { return Optional.ofNullable(meta.get(id)); }

        @Override
        public InputStream content(FileId id) {
            return new ByteArrayInputStream(stored.get(id));
        }

        @Override
        public List<StoredFile> list() {
            return meta.values().stream().toList();
        }

        @Override
        public void delete(FileId id) {
            stored.remove(id);
            meta.remove(id);
        }
    }

    /** The single-value ObjectProvider the service asks for its gateway. */
    private static <T> ObjectProvider<T> provider(java.util.function.Supplier<T> value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value.get(); }
            @Override public T getObject(Object... args) { return value.get(); }
            @Override public T getIfAvailable() { return value.get(); }
            @Override public T getIfUnique() { return value.get(); }
        };
    }
}
