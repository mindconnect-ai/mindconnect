package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import io.swagger.v3.oas.annotations.Operation;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attaches files to a chat session, Responses-API-style: multipart for
 * upload+attach in one call, or {@code {"fileId": ...}} for a file already in
 * the store. The heavy lifting (template, scoped store, ingestion workflow,
 * vector_search activation) lives in {@link ai.mindconnect.agentrest.service.SessionFileService},
 * shared with the chat UI.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/files")
public class SessionFilesApiController {

    private final FileStore fileStore;
    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;

    public SessionFilesApiController(FileStore fileStore,
                                  ai.mindconnect.agentrest.service.SessionFileService sessionFiles) {
        this.fileStore = fileStore;
        this.sessionFiles = sessionFiles;
    }

    /** Upload + attach in one call (multipart {@code file}). */
    @Operation(tags = "Sessions", summary = "Upload and attach a file to the chat",
            description = "Stores the file, ingests it into the session's vector store "
                    + "and activates vector_search for the agent — one call, "
                    + "Responses-API-style.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAndAttach(@PathVariable String sessionId,
                                                               @RequestParam("file") MultipartFile file)
            throws IOException {
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStore.save(file.getOriginalFilename(), file.getContentType(), content);
        }
        return ResponseEntity.ok(toResponse(sessionFiles.attach(SessionId.of(sessionId), stored)));
    }

    /** Attach a previously uploaded file by id ({@code {"fileId": "file-…"}}). */
    @Operation(tags = "Sessions", summary = "Attach an already-uploaded file to the chat",
            description = "Same as the multipart variant, for a file already in the file "
                    + "store: body {\"fileId\": \"file-…\"}.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> attachExisting(@PathVariable String sessionId,
                                                              @RequestBody Map<String, String> body) {
        String fileId = body.get("fileId");
        StoredFile stored = fileId == null ? null : fileStore.find(FileId.of(fileId)).orElse(null);
        if (stored == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown fileId '" + fileId + "' — upload via POST /api/files first."));
        }
        return ResponseEntity.ok(toResponse(sessionFiles.attach(SessionId.of(sessionId), stored)));
    }

    /**
     * The files attached to this chat: the file-store id, name, media type
     * and size of each, and for an ingested file the number of searchable
     * chunks it produced. An image is attached but not ingested — it goes to
     * the model with the next message — so its chunk count is 0.
     */
    @Operation(tags = "Sessions", summary = "List the files attached to a chat",
            description = "One entry per attached file: fileId (file store), name, mediaType, "
                    + "sizeBytes and chunks (searchable chunks; 0 for an image, which goes to "
                    + "the model with the next message instead of into the vector store).")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAttachments(@PathVariable String sessionId) {
        Map<String, Long> chunksByName = new java.util.HashMap<>();
        sessionFiles.listAttachments(SessionId.of(sessionId)).forEach((ingestedId, chunks) ->
                chunksByName.merge(java.nio.file.Path.of(ingestedId).getFileName().toString(), chunks, Long::sum));
        var files = sessionFiles.attachments(SessionId.of(sessionId)).stream()
                .map(f -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("fileId", f.id());
                    entry.put("name", f.name());
                    entry.put("mediaType", f.mediaType());
                    entry.put("sizeBytes", f.sizeBytes());
                    entry.put("chunks", chunksByName.getOrDefault(f.name(), 0L));
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(files);
    }

    /**
     * Detaches a file from the chat: its chunks leave the session's vector
     * store, so the agent can no longer search it, and the spooled copy goes
     * with them. The original in the file store is untouched \u2014 it may be
     * attached to other sessions.
     */
    @Operation(tags = "Sessions", summary = "Detach a file from the chat",
            description = "Removes the file's chunks from the session's vector store and the "
                    + "spooled copy. The file itself stays in the file store.")
    @DeleteMapping
    public ResponseEntity<Void> detach(@PathVariable String sessionId,
                                       @RequestParam("file") String fileIdOrName) {
        sessionFiles.deleteAttachment(SessionId.of(sessionId), fileIdOrName);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toResponse(
            ai.mindconnect.agentrest.service.SessionFileService.AttachResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("file", result.file());
        response.put("store", result.store());
        response.put("status", result.success() ? "completed" : "failed");
        if (!result.success()) {
            response.put("error", result.message());
        }
        return response;
    }
}
