package ai.mindconnect.agent.responses.controller;

import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.runtime.AgentRuntimeBackend;
import ai.mindconnect.filestore.FileStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI's Files API, as far as a Responses client needs it: upload a file
 * once, then name it by {@code file_id} in an {@code input_file} or
 * {@code input_image} part of any number of requests. The file lands in the
 * runtime's file store — the same one the chat's uploads use — and is
 * attached to a session only when a request references it.
 */
@RestController
@RequestMapping("/v1")
public class FilesController {

    private final AgentRuntimeBackend backend;
    private final ObjectProvider<FileStore> fileStore;

    public FilesController(AgentRuntimeBackend backend, ObjectProvider<FileStore> fileStore) {
        this.backend = backend;
        this.fileStore = fileStore;
    }

    /** {@code POST /v1/files} — multipart, {@code file} and {@code purpose}, as the SDKs send it. */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "purpose", required = false) String purpose)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload" : file.getOriginalFilename();
        StoredFile stored = backend.files().upload(name, file.getContentType(), file.getBytes());
        return ResponseEntity.ok(toDto(stored, purpose));
    }

    @GetMapping(value = "/files/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return backend.files().get(id)
                .map(f -> ResponseEntity.ok(toDto(f, null)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ResponsesController.error("not_found", "No file with id '" + id + "'.")));
    }

    @GetMapping("/files/{id}/content")
    public ResponseEntity<?> content(@PathVariable String id) throws IOException {
        FileStore store = fileStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("File support is not wired on this server");
        }
        var stored = store.find(id).orElse(null);
        if (stored == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ResponsesController.error("not_found", "No file with id '" + id + "'."));
        }
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (stored.contentType() != null) type = MediaType.parseMediaType(stored.contentType());
        } catch (RuntimeException ignore) {
            // an odd content type on the record; octet-stream will do
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + stored.name() + "\"")
                .body(new InputStreamResource(store.content(id)));
    }

    /** OpenAI's file object: {@code id, object, bytes, created_at, filename, purpose}. */
    static Map<String, Object> toDto(StoredFile file, String purpose) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", file.id());
        dto.put("object", "file");
        dto.put("bytes", file.sizeBytes());
        dto.put("created_at", System.currentTimeMillis() / 1000);
        dto.put("filename", file.filename());
        dto.put("purpose", purpose == null || purpose.isBlank() ? "user_data" : purpose);
        return dto;
    }
}
