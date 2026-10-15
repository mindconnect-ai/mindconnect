package ai.mindconnect.agentrest.dto;

import java.time.Instant;

/**
 * A transcription job as the polling endpoint reports it. Everything below
 * {@link #status} is filled once there is something to fill it with: the
 * transcript on {@code completed}, the reason on {@code failed}.
 *
 * @param status          queued, running, completed, failed, cancelled or suspended
 * @param text            the transcript
 * @param language        the language the model detected, when it reports one
 * @param durationSeconds length of the recording, when the model reports it
 * @param error           why the job failed
 * @param fileId          the recording this job read, still in the file store
 * @param fileUrl         where to fetch or delete it
 */
public record TranscriptionJobResult(
        String id,
        String status,
        String text,
        String language,
        Double durationSeconds,
        int inputTokens,
        int outputTokens,
        String error,
        String fileId,
        String fileUrl,
        Instant submittedAt,
        Instant endedAt
) {}
