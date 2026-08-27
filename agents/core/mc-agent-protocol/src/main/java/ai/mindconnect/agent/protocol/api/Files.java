package ai.mindconnect.agent.protocol.api;

import ai.mindconnect.agent.protocol.StoredFile;

import java.util.Optional;

/**
 * The fourth and smallest protocol surface: files a conversation can
 * reference. Upload once, then point at the file from message content
 * ({@code ContentPart.Document}/{@code Image}/{@code Audio} with a
 * {@code MediaSource.FileId}) — or hand it to a tool that reads it.
 */
public interface Files {

    StoredFile upload(String filename, String mediaType, byte[] content);

    Optional<StoredFile> get(String fileId);
}
