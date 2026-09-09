package ai.mindconnect.agentrest.dto;

/**
 * What a submitted transcription job answers with: its id, where to fetch the
 * result, where to watch it happen, and where the recording now lives. Every
 * URL is handed over rather than assembled by the caller, so a client never
 * has to know how they are built.
 *
 * @param id        the job's task id
 * @param status    its status right now, normally {@code queued}
 * @param resultUrl poll this for the status and, once done, the transcript
 * @param eventsUrl Server-Sent Events of this job, from its channel
 * @param channelId the channel the events are published on
 * @param fileId    the recording in the file store
 * @param fileUrl   that recording: {@code GET} for its metadata, {@code GET
 *                  …/content} for the bytes, {@code DELETE} when it has
 *                  served its purpose. Nothing deletes it for you.
 */
public record TranscriptionJobResponse(
        String id,
        String status,
        String resultUrl,
        String eventsUrl,
        String channelId,
        String fileId,
        String fileUrl
) {}
