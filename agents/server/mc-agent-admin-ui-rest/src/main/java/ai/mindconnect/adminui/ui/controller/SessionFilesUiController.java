package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.SessionFilesPage;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.SessionDirectories;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The session's Files dialog: browse the session's directories and view or
 * download what is in them — what the agent wrote with {@code bash} or
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

    private final AgentSessionService sessionService;
    private final AgentSessionRepository sessions;
    private final CurrentUsers currentUsers;
    /** Where the tools keep a session's files when not on this machine — a virtual environment server. */
    private final ObjectProvider<WorkspaceProvider> workspaces;

    public SessionFilesUiController(AgentSessionService sessionService, AgentSessionRepository sessions,
                                    CurrentUsers currentUsers) {
        this(sessionService, sessions, currentUsers, null);
    }

    @Autowired
    public SessionFilesUiController(AgentSessionService sessionService, AgentSessionRepository sessions,
                                    CurrentUsers currentUsers, ObjectProvider<WorkspaceProvider> workspaces) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.currentUsers = currentUsers;
        this.workspaces = workspaces;
    }

    /** The dialog: the session's directories as a tree. */
    @GetMapping
    public ResponseEntity<?> browse(@PathVariable("sessionId") String sessionIdValue,
                                    @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        Optional<SessionDirectories> directories = directoriesOfOwned(sessionId);
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
        Optional<SessionDirectories> directories = directoriesOfOwned(sessionId);
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
        Optional<SessionDirectories> directories = directoriesOfOwned(SessionId.of(sessionIdValue));
        Optional<SessionDirectories.Content> file = directories.isEmpty() ? Optional.empty()
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

    /** The session's directories, when the session exists and is the caller's. */
    private Optional<SessionDirectories> directoriesOfOwned(SessionId sessionId) {
        var caller = currentUsers.current().orElse(null);
        if (caller == null) return Optional.empty();
        return sessions.findById(sessionId)
                .filter(session -> caller.equals(session.userId()))
                .map(session -> {
                    WorkspaceProvider provider = workspaces == null ? null : workspaces.getIfAvailable();
                    if (provider == null) return sessionService.directories(sessionId);
                    // With an environment server the session's files are its workspace there: bash and
                    // code_execute write into it, and the uploads are copied into it (files() does that
                    // now). It is the only directory shown, under the path the tools and the container use.
                    ToolCallScope scope = new ToolCallScope(session.userId(), session.id(), session.agentDefinitionId(),
                            session.parentSessionId() == null ? session.id() : session.parentSessionId(),
                            session.workingDir(), session.additionalDirs());
                    var files = provider.files(scope, AgentTool.of("file_read"),
                            FileRoots.of(Path.of(session.hasWorkingDir() ? session.workingDir() : ".")));
                    return new SessionDirectories(List.of(), Map.of(files.roots().base().toString(), files));
                });
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
