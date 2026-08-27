package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * One file listing for a single workspace scope (SESSION / AGENT_USER /
 * USER). Renders a header with file count, an empty-state hint when
 * there are no files, otherwise one item per file with view/download
 * icon actions.
 *
 * <p>The {@code scopeKey} is the URL path segment for the scope
 * ({@code session} / {@code agent} / {@code user}) — it appears in the
 * file-download URLs and is part of the component id, so the same page
 * can hold three independent instances without id collisions.
 */
public final class WorkspaceScopeListComponent implements UiComponent {

    private final UUID sessionId;
    private final String scopeKey;
    private final String title;
    private final String emptyHint;
    private final WorkspaceStore store;
    private final WorkspaceScope scope;

    public WorkspaceScopeListComponent(UUID sessionId, String scopeKey, String title,
                                        String emptyHint, WorkspaceStore store, WorkspaceScope scope) {
        this.sessionId = sessionId;
        this.scopeKey = scopeKey;
        this.title = title;
        this.emptyHint = emptyHint;
        this.store = store;
        this.scope = scope;
    }

    @Override
    public String id() {
        return "ws-list-" + scopeKey + "-" + sessionId;
    }

    @Override
    public UiList render() {
        List<String> filenames = store.list(scope);
        String header = title + "  —  " + filenames.size() + " file"
                + (filenames.size() == 1 ? "" : "s");
        var list = UiList.of(id(), header);
        list.icon(scopeIcon(scopeKey));

        if (filenames.isEmpty()) {
            list.item(UiList.Item.of("empty-" + scopeKey, "(empty)")
                    .description(emptyHint));
            return list;
        }

        for (String name : filenames) {
            long bytes = store.sizeOf(scope, name).orElse(0L);
            String size = humanSize(bytes);
            String label = name;
            String viewUrl = "/admin/api/sessions/" + sessionId + "/workspace/"
                    + scopeKey + "/file?name=" + urlEncode(name);
            String downloadUrl = viewUrl + "&download=true";

            // Both actions go through the authenticated JS handler (download
            // builds a blob and uses <a download>, view opens the blob in a
            // new tab) so the Bearer token / session cookie is honoured —
            // direct anchor hrefs would skip auth.
            UiAction view = UiAction.icon("view-" + scopeKey + "-" + name, "View").icon("show")
                    .openBlob(viewUrl);
            UiAction download = UiAction.icon("dl-" + scopeKey + "-" + name, "Download").icon("download")
                    .download(downloadUrl, name);

            list.item(UiList.Item.of("ws-file-" + scopeKey + "-" + name, label)
                    .icon("file")
                    .description(size)
                    .action(view)
                    .action(download));
        }
        return list;
    }

    /** The scope's sprite icon — same visual language as the rest of the chrome. */
    private static String scopeIcon(String scopeKey) {
        return switch (scopeKey) {
            case "session" -> "folder";
            case "agent"   -> "database";
            case "user"    -> "user";
            default        -> "folder";
        };
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
