package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agentrest.service.SessionFileService;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;

/**
 * The chat UI's attach endpoint: same pipeline as the external
 * {@code POST /api/sessions/{id}/files} (via {@link SessionFileService}), but
 * the response is a {@link UiPatch} with a toast so the event bus can show
 * the outcome in place — the REST endpoint answers raw JSON instead.
 *
 * <p>Every endpoint here refuses a session that is not the caller's with a
 * 404, like every other session-addressed chat endpoint — an upload into a
 * stranger's chat would put words into their model's mouth, and a file's
 * bytes are theirs.
 */
@RestController
@RequestMapping("/chat/api/sessions/{sessionId}/chat-files")
public class ChatFilesUiController {

    private final FileStore fileStore;
    private final SessionFileService sessionFiles;
    private final AgentSessionRepository sessions;
    private final AgentSessionService sessionService;
    private final SessionAgentResolver agentResolver;
    private final SessionOwnership ownership;
    /** Whether a turn is live right now — the composer is redrawn in that state. */
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;
    /** What the Tools picker offers — the redrawn composer's tool badge counts over it. */
    private final ai.mindconnect.agent.tool.ToolRegistry toolRegistry;

    /** For the model button's label: the model behind the agent's config. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.mindconnect.llm.port.out.LlmConfigRepository> llmConfigs;

    public ChatFilesUiController(FileStore fileStore, SessionFileService sessionFiles,
                                 AgentSessionRepository sessions,
                                 AgentSessionService sessionService,
                                 AgentDefinitionRepository agents,
                                 SessionOwnership ownership,
                                 ai.mindconnect.chatui.service.ActiveStreams activeStreams,
                                 ai.mindconnect.agent.tool.ToolRegistry toolRegistry,
                                 org.springframework.beans.factory.ObjectProvider<
                                         ai.mindconnect.llm.port.out.LlmConfigRepository> llmConfigs) {
        this.llmConfigs = llmConfigs;
        this.fileStore = fileStore;
        this.sessionFiles = sessionFiles;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.agentResolver = new SessionAgentResolver(agents);
        this.ownership = ownership;
        this.activeStreams = activeStreams;
        this.toolRegistry = toolRegistry;
    }

    /**
     * The bytes of a file this chat holds — for the image a message bubble
     * shows inline. Served only to the session's owner and only for a file the
     * session references: one of its attachments, or a part of one of its
     * messages; any other id is not found, whether or not the store has it.
     */
    @org.springframework.web.bind.annotation.GetMapping("/{fileId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> content(
            @PathVariable("sessionId") String sessionIdValue, @PathVariable String fileId,
            @AuthenticationPrincipal OidcUser user) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (!referencedBySession(sessionId, user, fileId)) {
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

    /** Is this the caller's session, and does it hold this file — as an attachment, or as a part of one of its messages? */
    private boolean referencedBySession(SessionId sessionId, OidcUser user, String fileId) {
        var session = ownership.owned(sessionId, user).orElse(null);
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
                          @RequestParam("chat-attach") List<MultipartFile> files,
                          @AuthenticationPrincipal OidcUser user) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        requireOwned(sessionId, user);
        // The caller owns the session (checked above), so the uploads are the session owner's files.
        UserId owner = UserId.of(SessionOwnership.userIdOf(user));
        UiPatch patch = UiPatch.of();
        for (MultipartFile file : files) {
            StoredFile stored;
            try (InputStream content = file.getInputStream()) {
                stored = fileStore.save(file.getOriginalFilename(), file.getContentType(), content, owner);
            }
            SessionFileService.AttachResult result = sessionFiles.attach(sessionId, stored);
            patch.toast(result.success()
                    ? UiToast.success(result.message()).title("File attached")
                    : UiToast.error(result.message()).title("Attach failed"));
        }
        attachmentsPanels(sessionId).forEach(patch::patch);
        patch.patch(attachmentCountRefresh(sessionId));
        return patch;
    }

    /**
     * Both attached-files panels, documents and images, refreshed from the
     * session's record with the chunks each ingested file produced. Only one
     * of them is on the page at a time — whichever dialog is open — and a
     * REPLACE whose target is not there is skipped, so both always go out.
     */
    private List<UiPatch.Operation> attachmentsPanels(SessionId sessionId) {
        var files = sessionFiles.attachments(sessionId);
        var chunks = sessionFiles.listAttachments(sessionId);
        return java.util.Arrays.stream(ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.Kind.values())
                .map(kind -> UiPatch.Operation.replace(kind.panelId(),
                        ai.mindconnect.chatui.ui.component.ChatAttachmentsComponent.node(sessionId, kind, files, chunks)))
                .toList();
    }

    /** Detaches a file by name: its chunks leave the session store, an image leaves the record. */
    @org.springframework.web.bind.annotation.DeleteMapping
    public UiPatch remove(@PathVariable("sessionId") String sessionIdValue, @RequestParam("file") String fileName,
                          @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        requireOwned(sessionId, user);
        sessionFiles.deleteAttachment(sessionId, fileName);
        UiPatch patch = UiPatch.of();
        attachmentsPanels(sessionId).forEach(patch::patch);
        return patch
                .patch(attachmentCountRefresh(sessionId))
                .toast(UiToast.success("Removed from the conversation.").title("File removed"));
    }

    private void requireOwned(SessionId sessionId, OidcUser user) {
        if (ownership.owned(sessionId, user).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * The composer carries the number of attached files on its "+", so a file
     * arriving or leaving has to redraw it — otherwise the count keeps saying
     * what was true before the upload until the page is reloaded. The tool
     * count rides along for the same reason: the menu behind the "+" shows
     * both, and half a rebuilt composer would state one of them stale.
     */
    private UiPatch.Operation attachmentCountRefresh(SessionId sessionId) {
        var session = sessions.findById(sessionId).orElse(null);
        var agent = session == null ? null : agentResolver.resolve(session);
        // Whichever state the composer is in stays: a file attached mid-turn
        // must not swap the Stop button for a Send button.
        boolean streaming = activeStreams.findHandle(
                SessionOwnership.channelOf(sessionId)).isPresent();
        var form = new ai.mindconnect.chatui.ui.component.ChatFormComponent(
                        sessionId, agent == null ? null : agent.id(), streaming)
                .withModelLabel(agent == null ? null : ai.mindconnect.chatui.ui.component.ModelLabel.of(
                        llmConfigs == null ? null : llmConfigs.getIfAvailable(), agent.llmConfigName()))
                .withAttachments(sessionFiles.attachments(sessionId))
                .withAgentCounts(agent, ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent
                        .offered(toolRegistry.toolNamesByGroup()))
                .withWorkingDir(session == null ? null : session.workingDir())
                .withDirChoice(sessionService.workingDirChoice());
        return UiPatch.Operation.replace(form.id(), form.render());
    }
}
