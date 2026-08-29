package ai.mindconnect.chatui.ui.controller;

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
@RequestMapping("/chat/api/sessions/{sessionId}/chat-files")
public class ChatFilesUiController {

    private final FileStore fileStore;
    private final SessionFileService sessionFiles;
    private final ai.mindconnect.agent.port.out.AgentSessionRepository sessions;
    private final ai.mindconnect.agent.service.SessionAgentResolver agentResolver;

    public ChatFilesUiController(FileStore fileStore, SessionFileService sessionFiles,
                                 ai.mindconnect.agent.port.out.AgentSessionRepository sessions,
                                 ai.mindconnect.agent.port.out.AgentDefinitionRepository agents) {
        this.fileStore = fileStore;
        this.sessionFiles = sessionFiles;
        this.sessions = sessions;
        this.agentResolver = new ai.mindconnect.agent.service.SessionAgentResolver(agents);
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
                ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.node(sessionId, sessionFiles.listAttachments(sessionId))));
        patch.patch(attachmentCountRefresh(sessionId));
        return patch;
    }

    /** Removes a file's chunks from the session store (query param: slashes in ids). */
    @org.springframework.web.bind.annotation.DeleteMapping
    public UiPatch remove(@PathVariable UUID sessionId, @RequestParam("file") String fileId) {
        sessionFiles.deleteAttachment(sessionId, fileId);
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("chat-attachments",
                        ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.node(sessionId, sessionFiles.listAttachments(sessionId))))
                .patch(attachmentCountRefresh(sessionId))
                .toast(UiToast.success("Removed from the conversation.").title("File removed"));
    }

    /**
     * The composer carries the number of attached files on its "+", so a file
     * arriving or leaving has to redraw it — otherwise the count keeps saying
     * what was true before the upload until the page is reloaded.
     */
    private UiPatch.Operation attachmentCountRefresh(UUID sessionId) {
        var agent = sessions.findById(sessionId).map(agentResolver::resolve).orElse(null);
        var form = new ai.mindconnect.chatui.ui.component.ChatFormComponent(
                        sessionId, agent == null ? null : agent.id(), false)
                .withModelLabel(agent == null ? null : agent.llmConfigName())
                .withAttachmentCount(sessionFiles.listAttachments(sessionId).size());
        return form.reset();
    }
}
