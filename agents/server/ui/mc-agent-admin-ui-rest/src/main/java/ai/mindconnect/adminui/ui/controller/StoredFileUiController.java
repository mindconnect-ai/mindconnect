package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.FileUsageDialogComponent;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.agentrest.service.FileUsageService;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
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
 * The files tab of the vector stores screen: the stored files the Files API
 * gives the signed-in user — their own and those stored before creators were
 * recorded. Only a file's uploader deletes it.
 */
@RestController
@RequestMapping("/admin/vector-stores/files")
public class StoredFileUiController {

    private final FileStore fileStore;
    private final CurrentUsers currentUsers;
    private final FileUsageService fileUsage;
    private final VectorStoreOverview overview;

    StoredFileUiController(FileStore fileStore, CurrentUsers currentUsers, FileUsageService fileUsage,
                           VectorStoreOverview overview) {
        this.fileStore = fileStore;
        this.currentUsers = currentUsers;
        this.fileUsage = fileUsage;
        this.overview = overview;
    }

    /** The uploads are stored as the signed-in user's files — the file store records who put them there. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UiPage uploadFiles(@RequestParam("vs-files-upload") List<MultipartFile> uploads) {
        UserId creator = currentUsers.require();
        for (var upload : uploads) {
            try (var content = upload.getInputStream()) {
                fileStore.save(upload.getOriginalFilename(), upload.getContentType(), content, creator);
            } catch (IOException e) {
                throw new IllegalStateException("Upload failed: " + e.getMessage(), e);
            }
        }
        return overview.page("files");
    }

    /**
     * The files tab's Download button. The Files API takes bearer tokens only,
     * so the browser downloads here, with its session — and gets what the Files
     * API would give the signed-in user: their own files and those stored
     * before creators were recorded; anything else is 404.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> downloadStoredFile(@PathVariable String id) throws IOException {
        UserId caller = currentUsers.require();
        var file = fileStore.find(FileId.of(id))
                .filter(f -> f.readableBy(caller)).orElse(null);
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

    /** Where the file is used, as a dialog over the files tab. */
    @GetMapping("/{id}/usage")
    public UiPatch fileUsage(@PathVariable String id) {
        UserId caller = currentUsers.require();
        var file = fileStore.find(FileId.of(id))
                .filter(f -> f.readableBy(caller)).orElse(null);
        if (file == null) {
            return UiPatch.of().toast(UiToast.error("There is no file '" + id + "'."));
        }
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(FileUsageDialogComponent.ID))
                .patch(UiPatch.Operation.append("sui-dialogs",
                        new FileUsageDialogComponent(file, fileUsage.of(caller)).render()));
    }

    /** Only the uploader deletes a file — the Files API's rule; a file without a creator stays. */
    @DeleteMapping("/{id}")
    public UiPage deleteStoredFile(@PathVariable String id) throws IOException {
        var fileId = FileId.of(id);
        if (fileStore.find(fileId).filter(f -> f.createdBy(currentUsers.require())).isEmpty()) {
            return overview.page("files").toast(UiToast.error("Only the user who uploaded a file can delete it."));
        }
        fileStore.delete(fileId);
        return overview.page("files");
    }
}
