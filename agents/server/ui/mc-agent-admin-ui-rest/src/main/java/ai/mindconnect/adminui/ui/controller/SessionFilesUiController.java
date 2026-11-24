package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.SessionFilesPage;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.directory.SessionFileBrowser;
import ai.mindconnect.agent.management.directory.UserHome;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.chatui.ui.component.DirectoryPickerComponent;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The session's Files dialog: browse the session's directories and view,
 * download, edit or delete what is in them — what the agent wrote with {@code bash} or
 * {@code code_execute}, which reaches the user no other way.
 *
 * <p>Only the session's owner gets anything; any other session is not found.
 * These are real files from real directories, a project checkout among them,
 * and the other session dialogs' "any signed-in user" is too wide for that.
 */
@RestController
@RequestMapping("/admin/api/sessions/{sessionId}/files")
public class SessionFilesUiController {

    /**
     * What a file is shown as when viewed. Anything that could run script in
     * the admin's origin — HTML, SVG, XML — is shown as its source; a type not
     * listed here is only downloaded.
     */
    private static final Map<String, String> VIEWABLE = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"), Map.entry("ico", "image/x-icon"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("json", "application/json"),
            Map.entry("csv", "text/csv;charset=UTF-8"), Map.entry("tsv", "text/tab-separated-values;charset=UTF-8"),
            Map.entry("md", "text/markdown;charset=UTF-8"),
            Map.entry("txt", "text/plain;charset=UTF-8"), Map.entry("log", "text/plain;charset=UTF-8"),
            Map.entry("html", "text/plain;charset=UTF-8"), Map.entry("htm", "text/plain;charset=UTF-8"),
            Map.entry("svg", "text/plain;charset=UTF-8"), Map.entry("xml", "text/plain;charset=UTF-8"),
            Map.entry("yaml", "text/plain;charset=UTF-8"), Map.entry("yml", "text/plain;charset=UTF-8"),
            Map.entry("py", "text/plain;charset=UTF-8"), Map.entry("java", "text/plain;charset=UTF-8"),
            Map.entry("js", "text/plain;charset=UTF-8"), Map.entry("ts", "text/plain;charset=UTF-8"),
            Map.entry("sh", "text/plain;charset=UTF-8"), Map.entry("sql", "text/plain;charset=UTF-8"));

    private final AgentRuntime runtime;
    private final CurrentUsers currentUsers;
    private final UserHome userHome;
    /** Where the tools keep a session's files when not on this machine — a virtual environment server. */
    private final ObjectProvider<WorkspaceProvider> workspaces;

    public SessionFilesUiController(AgentRuntime runtime, CurrentUsers currentUsers, UserHome userHome,
                                    ObjectProvider<WorkspaceProvider> workspaces) {
        this.runtime = runtime;
        this.currentUsers = currentUsers;
        this.userHome = userHome;
        this.workspaces = workspaces;
    }

    /** The dialog: the session's directories as a tree. */
    @GetMapping
    public ResponseEntity<?> browse(@PathVariable("sessionId") String sessionIdValue,
                                    @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionFileBrowser> directories = directoriesOfOwned(sessionId);
        if (directories.isEmpty()) return ResponseEntity.notFound().build();
        UiPage page = new SessionFilesPage(sessionId, directories.get()).render();
        return dialog ? SessionUiController.sessionDialog(sessionId, "Files", page) : ResponseEntity.ok(page);
    }

    /** One folder filled and open, replacing its node — for a folder the dialog did not fill up front. */
    @GetMapping("/folder")
    public ResponseEntity<UiPatch> folder(@PathVariable("sessionId") String sessionIdValue,
                                          @RequestParam("root") String root,
                                          @RequestParam(value = "path", defaultValue = "") String path) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionFileBrowser> directories = directoriesOfOwned(sessionId);
        if (directories.isEmpty() || directories.get().list(root, path).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SessionFilesPage(sessionId, directories.get()).expanded(root, path));
    }

    /** One file's bytes — as an attachment with {@code download}, else in a form safe to view. */
    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable("sessionId") String sessionIdValue,
                                                       @RequestParam("root") String root,
                                                       @RequestParam("path") String path,
                                                       @RequestParam(value = "download", defaultValue = "false") boolean download)
            throws IOException {
        Optional<SessionFileBrowser> directories = directoriesOfOwned(SessionId.of(sessionIdValue));
        Optional<SessionFileBrowser.Content> file = directories.isEmpty() ? Optional.empty()
                : directories.get().open(root, path);
        if (file.isEmpty()) return ResponseEntity.notFound().build();
        String name = file.get().name();
        String viewAs = download ? null : VIEWABLE.get(extension(name));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(file.get().size());
        headers.set("X-Content-Type-Options", "nosniff");
        if (viewAs == null) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
        } else {
            headers.setContentType(MediaType.parseMediaType(viewAs));
            headers.setContentDisposition(ContentDisposition.inline().filename(name).build());
        }
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(file.get().stream()));
    }

    /** A file in the viewer, in the tree's place: its text to edit, a frame, or a note to download it. */
    @GetMapping("/view")
    public ResponseEntity<UiPatch> view(@PathVariable("sessionId") String sessionIdValue,
                                        @RequestParam("root") String root,
                                        @RequestParam("path") String path) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionFileBrowser> directories = directoriesOfOwned(sessionId);
        Optional<SessionFileBrowser.Preview> preview = directories.isEmpty() ? Optional.empty()
                : directories.get().preview(root, path);
        if (preview.isEmpty()) {
            return ResponseEntity.ok(SessionFilesPage.closed().toast(UiToast.error("The file is gone.")));
        }
        return ResponseEntity.ok(new SessionFilesPage(sessionId, directories.get()).opened(root, preview.get()));
    }

    /** The editor's text written back to its file; the file must still be there. */
    @PostMapping("/save")
    public ResponseEntity<UiPatch> save(@PathVariable("sessionId") String sessionIdValue,
                                        @RequestParam("root") String root,
                                        @RequestParam("path") String path,
                                        @RequestBody Map<String, Object> raw) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionFileBrowser> directories = directoriesOfOwned(sessionId);
        if (directories.isEmpty()) return ResponseEntity.notFound().build();
        Object content = raw.get("content");
        Optional<SessionFileBrowser.Entry> saved;
        try {
            saved = directories.get().write(root, path, content == null ? "" : String.valueOf(content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(UiPatch.of().toast(UiToast.error("Not saved: the text is larger than "
                    + SessionFilesPage.humanSize(SessionFileBrowser.MAX_EDIT_BYTES) + ".")));
        }
        if (saved.isEmpty()) {
            return ResponseEntity.ok(UiPatch.of().toast(UiToast.error("Not saved: the file is gone.")));
        }
        return ResponseEntity.ok(new SessionFilesPage(sessionId, directories.get()).saved(root, saved.get()));
    }

    /** A file, or a folder with everything in it, deleted; a directory of the session itself never is. */
    @PostMapping("/delete")
    public ResponseEntity<UiPatch> delete(@PathVariable("sessionId") String sessionIdValue,
                                          @RequestParam("root") String root,
                                          @RequestParam("path") String path) throws IOException {
        Optional<SessionFileBrowser> directories = directoriesOfOwned(SessionId.of(sessionIdValue));
        if (directories.isEmpty()) return ResponseEntity.notFound().build();
        if (!directories.get().delete(root, path)) {
            return ResponseEntity.ok(UiPatch.of().toast(UiToast.error("Nothing to delete there.")));
        }
        return ResponseEntity.ok(SessionFilesPage.deleted(root, path));
    }

    /**
     * A folder and everything below it as a zip download, written straight to the
     * response on the request's own thread. A folder past the archive limits is refused
     * before anything is written.
     */
    @GetMapping("/zip")
    public void zip(@PathVariable("sessionId") String sessionIdValue,
                    @RequestParam("root") String root,
                    @RequestParam(value = "path", defaultValue = "") String path,
                    HttpServletResponse response) throws IOException {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionFileBrowser> directories = directoriesOfOwned(sessionId);
        // A directory is named the way the dialog shows it: the chat's own is not its session id.
        String name = path.isBlank() ? DirectoryPickerComponent.label(root, sessionId) : null;
        Optional<SessionFileBrowser.Archive> archive = directories.isEmpty() ? Optional.empty()
                : directories.get().archive(root, path, name);
        if (archive.isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (archive.get().tooLarge()) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "The folder is too large to download as a zip "
                    + "(at most " + SessionFileBrowser.MAX_ARCHIVE_FILES + " files and "
                    + SessionFilesPage.humanSize(SessionFileBrowser.MAX_ARCHIVE_BYTES) + ").");
            return;
        }
        response.setContentType("application/zip");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(archive.get().name()).build().toString());
        archive.get().writeTo(response.getOutputStream());
        response.flushBuffer();
    }

    /** The session's directories, when the session exists and is the caller's. */
    private Optional<SessionFileBrowser> directoriesOfOwned(SessionId sessionId) {
        var caller = currentUsers.current().orElse(null);
        if (caller == null) return Optional.empty();
        return runtime.service(SessionManagement.class)
                .find(sessionId)
                .filter(session -> session.userId() != null && caller.value().equals(session.userId().value()))
                .map(session -> {
                    WorkspaceProvider provider = workspaces.getIfAvailable();
                    if (provider == null) return SessionFileBrowser.of(session, userHome);
                    // With an environment server the session's files are its workspace there: bash and
                    // code_execute write into it, and the uploads are copied into it (files() does that
                    // now). It is the only directory shown, under the path the tools and the container use.
                    String working = session.directories().working();
                    var files = provider.files(scope(session), AgentTool.of("file_read"),
                            FileRoots.of(Path.of(working == null || working.isBlank() ? "." : working)));
                    return new SessionFileBrowser(List.of(), Map.of(files.roots().base().toString(), files));
                });
    }

    private static ToolCallScope scope(ManagedSession session) {
        SessionId id = session.id();
        return new ToolCallScope(session.userId(), id,
                session.agentId().orElse(null),
                session.parentId() == null ? id : session.parentId(),
                session.directories().working(), session.directories().additional());
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
