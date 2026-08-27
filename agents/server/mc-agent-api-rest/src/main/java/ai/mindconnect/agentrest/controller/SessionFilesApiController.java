package ai.mindconnect.agentrest.controller;

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
import java.util.UUID;

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
    public ResponseEntity<Map<String, Object>> uploadAndAttach(@PathVariable UUID sessionId,
                                                               @RequestParam("file") MultipartFile file)
            throws IOException {
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStore.save(file.getOriginalFilename(), file.getContentType(), content);
        }
        return ResponseEntity.ok(toResponse(sessionFiles.attach(sessionId, stored)));
    }

    /** Attach a previously uploaded file by id ({@code {"fileId": "file-…"}}). */
    @Operation(tags = "Sessions", summary = "Attach an already-uploaded file to the chat",
            description = "Same as the multipart variant, for a file already in the file "
                    + "store: body {\"fileId\": \"file-…\"}.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> attachExisting(@PathVariable UUID sessionId,
                                                              @RequestBody Map<String, String> body) {
        String fileId = body.get("fileId");
        StoredFile stored = fileId == null ? null : fileStore.find(fileId).orElse(null);
        if (stored == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown fileId '" + fileId + "' — upload via POST /api/files first."));
        }
        return ResponseEntity.ok(toResponse(sessionFiles.attach(sessionId, stored)));
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
