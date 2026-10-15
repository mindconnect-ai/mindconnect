package ai.mindconnect.agent.protocol;

/**
 * A file stored with the backend, referenced from message content via
 * {@code ContentPart.MediaSource.FileId}. The id is backend-native
 * ({@code file-…} at OpenAI, a store id in our runtime).
 */
public record StoredFile(String id, String filename, String mediaType, long sizeBytes) {}
