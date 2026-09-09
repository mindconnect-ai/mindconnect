package ai.mindconnect.agentrest.service;

/**
 * One thing that happened to a transcription job, as a client sees it on the
 * job's channel. {@code status} is the task's status at that moment; the
 * remaining fields are filled when they are known — the transcript on
 * {@code completed}, the reason on {@code failed}.
 *
 * @param status   queued, running, delta, completed, failed or cancelled
 * @param text     the transcript, on completion
 * @param language the language the model detected, when it reports one
 * @param error    why the job failed, when it did
 */
public record TranscriptionEvent(
        String status,
        String text,
        String language,
        String error
) {
    public static TranscriptionEvent status(String status) {
        return new TranscriptionEvent(status, null, null, null);
    }

    /**
     * A piece of the transcript as it forms. {@code text} is the fragment,
     * not the whole — a client appends it. Whether these arrive one word at a
     * time or all at once at the end is the model's business.
     */
    public static TranscriptionEvent delta(String fragment) {
        return new TranscriptionEvent("delta", fragment, null, null);
    }

    public static TranscriptionEvent completed(String text, String language) {
        return new TranscriptionEvent("completed", text, language, null);
    }

    public static TranscriptionEvent failed(String error) {
        return new TranscriptionEvent("failed", null, null, error);
    }
}
