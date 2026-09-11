package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.filestore.FileId;
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
import java.util.Optional;

/**
 * Files API, OpenAI-style: upload once, reference the returned id from chats
 * and vector stores. Pure storage — associations (which session, which store)
 * live at their own endpoints.
 *
 * <p>A file belongs to the caller who uploaded it, its
 * {@link StoredFile#creator() creator}:
 * <ul>
 *   <li>the list holds the caller's own files and nothing else;</li>
 *   <li>metadata and content by id answer the creator — and, for a file
 *       stored before creators were recorded, whoever holds its id
 *       ({@link StoredFile#readableBy}), because chats and transcripts still
 *       reference those by id; such a file is never listed;</li>
 *   <li>only the creator deletes, so a file without a creator is no longer
 *       deleted through this API.</li>
 * </ul>
 * Anything else — someone else's file, an id that does not exist — answers
 * 404, and the two are indistinguishable on purpose.
 */
@Tag(name = "Files", description = "File storage, OpenAI-style: upload once, reference "
        + "the returned id from chat sessions and vector stores. A file belongs to the caller "
        + "who uploaded it.")
@RestController
@RequestMapping("/api/files")
public class FilesApiController {

    private final FileStore fileStore;

    public FilesApiController(FileStore fileStore) {
        this.fileStore = fileStore;
    }

    @Operation(summary = "Upload a file",
            description = "Stores the file as the caller's and returns its metadata; the id is "
                    + "the handle for chat attach (POST /api/sessions/{id}/files) and vector-store "
                    + "ingestion (POST /api/vector-stores/stores/{name}/ingest).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredFile upload(@RequestParam("file") MultipartFile file, @CurrentUser UserId caller)
            throws IOException {
        try (var content = file.getInputStream()) {
            return fileStore.save(file.getOriginalFilename(), file.getContentType(), content, caller);
        }
    }

    @Operation(summary = "List the caller's files")
    @GetMapping
    public List<StoredFile> list(@CurrentUser UserId caller) {
        return fileStore.list().stream().filter(file -> file.createdBy(caller)).toList();
    }

    @Operation(summary = "Get file metadata")
    @GetMapping("/{id}")
    public ResponseEntity<StoredFile> find(@PathVariable String id, @CurrentUser UserId caller) {
        return readable(id, caller).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Download the file content")
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String id, @CurrentUser UserId caller)
            throws IOException {
        var file = readable(id, caller).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + file.name() + "\"")
                .contentType(file.contentType() != null
                        ? MediaType.parseMediaType(file.contentType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(fileStore.content(file.id())));
    }

    @Operation(summary = "Delete a file",
            description = "Removes the caller's stored file; 404 for a file the caller did not "
                    + "upload. Chunks already ingested into vector stores stay.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser UserId caller) throws IOException {
        FileId fileId = FileId.of(id);
        if (fileStore.find(fileId).filter(file -> file.createdBy(caller)).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        fileStore.delete(fileId);
        return ResponseEntity.noContent().build();
    }

    private Optional<StoredFile> readable(String id, UserId caller) {
        return fileStore.find(FileId.of(id)).filter(file -> file.readableBy(caller));
    }
}
