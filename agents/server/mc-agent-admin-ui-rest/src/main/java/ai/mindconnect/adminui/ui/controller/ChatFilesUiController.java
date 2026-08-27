package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agentrest.service.SessionFileService;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * The chat UI's attach endpoint: same pipeline as the external
 * {@code POST /api/sessions/{id}/files} (via {@link SessionFileService}), but
 * the response is a {@link UiPatch} with a toast so the event bus can show
 * the outcome in place — the REST endpoint answers raw JSON instead.
 */
@RestController
@RequestMapping("/admin/api/sessions/{sessionId}/chat-files")
public class ChatFilesUiController {

    private final FileStore fileStore;
    private final SessionFileService sessionFiles;

    public ChatFilesUiController(FileStore fileStore, SessionFileService sessionFiles) {
        this.fileStore = fileStore;
        this.sessionFiles = sessionFiles;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UiPatch attach(@PathVariable UUID sessionId,
                          @RequestParam("chat-attach") List<MultipartFile> files) throws IOException {
        UiPatch patch = UiPatch.of();
        for (MultipartFile file : files) {
            StoredFile stored;
            try (InputStream content = file.getInputStream()) {
                stored = fileStore.save(file.getOriginalFilename(), file.getContentType(), content);
            }
            SessionFileService.AttachResult result = sessionFiles.attach(sessionId, stored);
            patch.toast(result.success()
                    ? UiToast.success(result.message()).title("File attached")
                    : UiToast.error(result.message()).title("Attach failed"));
        }
        patch.patch(UiPatch.Operation.replace("chat-attachments",
                ai.mindconnect.adminui.ui.component.ChatAttachmentsComponent.node(sessionId, sessionFiles.listAttachments(sessionId))));
        return patch;
    }

    /** Removes a file's chunks from the session store (query param: slashes in ids). */
    @org.springframework.web.bind.annotation.DeleteMapping
    public UiPatch remove(@PathVariable UUID sessionId, @RequestParam("file") String fileId) {
        sessionFiles.deleteAttachment(sessionId, fileId);
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("chat-attachments",
                        ai.mindconnect.adminui.ui.component.ChatAttachmentsComponent.node(sessionId, sessionFiles.listAttachments(sessionId))))
                .toast(UiToast.success("Removed from the conversation.").title("File removed"));
    }
}
