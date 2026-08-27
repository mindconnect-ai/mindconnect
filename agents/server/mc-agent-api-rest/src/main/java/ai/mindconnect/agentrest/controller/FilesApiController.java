package ai.mindconnect.agentrest.controller;

import ai.mindconnect.filestore.FileStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ai.mindconnect.filestore.StoredFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Files API, OpenAI-style: upload once, reference the returned id from chats
 * and vector stores. Pure storage — associations (which session, which store)
 * live at their own endpoints.
 */
@Tag(name = "Files", description = "File storage, OpenAI-style: upload once, reference "
        + "the returned id from chat sessions and vector stores.")
@RestController
@RequestMapping("/api/files")
public class FilesApiController {

    private final FileStore fileStore;

    public FilesApiController(FileStore fileStore) {
        this.fileStore = fileStore;
    }

    @Operation(summary = "Upload a file",
            description = "Stores the file and returns its metadata; the id is the handle "
                    + "for chat attach (POST /api/sessions/{id}/files) and vector-store "
                    + "ingestion (POST /api/vector-stores/stores/{name}/ingest).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredFile upload(@RequestParam("file") MultipartFile file) throws IOException {
        try (var content = file.getInputStream()) {
            return fileStore.save(file.getOriginalFilename(), file.getContentType(), content);
        }
    }

    @Operation(summary = "List stored files")
    @GetMapping
    public List<StoredFile> list() {
        return fileStore.list();
    }

    @Operation(summary = "Get file metadata")
    @GetMapping("/{id}")
    public ResponseEntity<StoredFile> find(@PathVariable String id) {
        return fileStore.find(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Download the file content")
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String id) throws IOException {
        var file = fileStore.find(id).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + file.name() + "\"")
                .contentType(file.contentType() != null
                        ? MediaType.parseMediaType(file.contentType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(fileStore.content(id)));
    }

    @Operation(summary = "Delete a file",
            description = "Removes the stored file. Chunks already ingested into vector "
                    + "stores stay.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        fileStore.delete(id);
        return ResponseEntity.noContent().build();
    }
}
