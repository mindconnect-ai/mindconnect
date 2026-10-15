package ai.mindconnect.agentrest.dto;

import java.util.List;

/**
 * The JSON body of {@code POST /api/sessions/{id}/chat}: the text, plus the
 * files sent with it — uploaded beforehand through {@code POST /api/files}
 * and referenced by id here, never carried inline. {@code kind} is
 * {@code image} or {@code file}; the file's name, media type and size come
 * from the store.
 */
public record ChatRequest(String message, List<Part> parts) {

    public record Part(String kind, String fileId) {}
}
