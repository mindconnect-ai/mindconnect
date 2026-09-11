package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agentrest.service.SessionFileService;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
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
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.filestore.FileId;

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
    private final AgentSessionRepository sessions;
    private final AgentSessionService sessionService;
    private final SessionAgentResolver agentResolver;

    public ChatFilesUiController(FileStore fileStore, SessionFileService sessionFiles,
                                 AgentSessionRepository sessions,
                                 AgentSessionService sessionService,
                                 AgentDefinitionRepository agents) {
        this.fileStore = fileStore;
        this.sessionFiles = sessionFiles;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.agentResolver = new SessionAgentResolver(agents);
    }

    /**
     * The bytes of a file this chat holds — for the image a message bubble
     * shows inline. Served only for a file the session references: one of
     * its attachments, or a part of one of its messages; any other id is
     * not found, whether or not the store has it.
     */
    @org.springframework.web.bind.annotation.GetMapping("/{fileId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> content(
            @PathVariable("sessionId") String sessionIdValue, @PathVariable String fileId) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (!referencedBySession(sessionId, fileId)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        StoredFile file = fileStore.find(FileId.of(fileId)).orElse(null);
        if (file == null) return org.springframework.http.ResponseEntity.notFound().build();
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (file.contentType() != null) type = MediaType.parseMediaType(file.contentType());
        } catch (org.springframework.http.InvalidMediaTypeException ignored) {
            // an odd content type from the upload — served as bytes
        }
        return org.springframework.http.ResponseEntity.ok()
                .contentType(type)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + file.name().replace("\"", "") + "\"")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new org.springframework.core.io.InputStreamResource(fileStore.content(FileId.of(fileId))));
    }

    /** Does the session hold this file — as an attachment, or as a part of one of its messages? */
    private boolean referencedBySession(SessionId sessionId, String fileId) {
        var session = sessions.findById(sessionId).orElse(null);
        if (session == null) return false;
        if (session.attachedFiles().stream().anyMatch(f -> fileId.equals(f.id()))) return true;
        return sessionService.loadHistory(sessionId).stream()
                .filter(m -> m.parts() != null)
                .flatMap(m -> m.parts().stream())
                .anyMatch(part -> part instanceof ai.mindconnect.message.domain.ContentPart.Media media
                        && fileId.equals(media.fileId()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UiPatch attach(@PathVariable("sessionId") String sessionIdValue,
                          @RequestParam("chat-attach") List<MultipartFile> files) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
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
                attachmentsPanel(sessionId)));
        patch.patch(attachmentCountRefresh(sessionId));
        return patch;
    }

    /** The attached-files panel: the session's record, with the chunks each ingested file produced. */
    private ai.mindconnect.ui.model.UiNode attachmentsPanel(SessionId sessionId) {
        return ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.node(
                sessionId, sessionFiles.attachments(sessionId), sessionFiles.listAttachments(sessionId));
    }

    /** Detaches a file by name: its chunks leave the session store, an image leaves the record. */
    @org.springframework.web.bind.annotation.DeleteMapping
    public UiPatch remove(@PathVariable("sessionId") String sessionIdValue, @RequestParam("file") String fileName) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        sessionFiles.deleteAttachment(sessionId, fileName);
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("chat-attachments",
                        attachmentsPanel(sessionId)))
                .patch(attachmentCountRefresh(sessionId))
                .toast(UiToast.success("Removed from the conversation.").title("File removed"));
    }

    /**
     * The composer carries the number of attached files on its "+", so a file
     * arriving or leaving has to redraw it — otherwise the count keeps saying
     * what was true before the upload until the page is reloaded.
     */
    private UiPatch.Operation attachmentCountRefresh(SessionId sessionId) {
        var agent = sessions.findById(sessionId).map(agentResolver::resolve).orElse(null);
        var form = new ai.mindconnect.chatui.ui.component.ChatFormComponent(
                        sessionId, agent == null ? null : agent.id(), false)
                .withModelLabel(agent == null ? null : agent.llmConfigName())
                .withAttachmentCount(sessionFiles.attachments(sessionId).size());
        return form.reset();
    }
}
