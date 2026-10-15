package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.service.SessionDirectories;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiIFrame;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiScrollPane;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.ui.model.UiTree;
import ai.mindconnect.ui.model.UiTreeNode;
import ai.mindconnect.ui.model.UiTrigger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The files in a session's directories — its working directory, the
 * additional ones and its own — for picking up what the agent produced with
 * {@code bash} or {@code code_execute}.
 *
 * <p>A tree in a scroll pane: every directory is a node that opens and closes,
 * and so is every folder in it. Folders are filled up front while the dialog
 * stays small ({@link #EAGER_ENTRIES}, {@link #EAGER_DEPTH}); past that a
 * folder loads its entries when asked, replacing just its own node, so a
 * project checkout with thousands of files neither slows the dialog down nor
 * floods it.
 *
 * <p>A file opens in a viewer that takes the tree's place until it is closed,
 * so the tree keeps its open folders: text of up to
 * {@link SessionDirectories#MAX_EDIT_BYTES} in an editor, images scaled to fit, PDFs in a
 * frame, anything else — Office documents among them — as a download only.
 * Files and folders can be deleted, a folder with everything in it.
 */
public class SessionFilesPage extends AdminPage {

    /** Entries rendered before folders stop being filled up front. */
    static final int EAGER_ENTRIES = 300;
    /** Folder levels below a directory that are filled up front. */
    static final int EAGER_DEPTH = 3;

    static final String SCROLL_ID = "files-scroll";
    static final String VIEWER_ID = "files-viewer";
    static final String EDITOR_FORM_ID = "files-editor";

    /** Shown as a picture. SVG is not among them: it is text, and edited as such. */
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico");
    /** Never edited as text, whatever their first bytes look like: documents and archives are zips. */
    private static final Set<String> BINARY = Set.of("docx", "pptx", "xlsx", "doc", "ppt", "xls", "odt", "ods", "odp",
            "zip", "jar", "gz", "tgz", "tar", "7z", "rar", "pdf", "exe", "dll", "so", "dylib", "class", "bin",
            "mp3", "mp4", "mov", "wav", "avi", "mkv", "woff", "woff2", "ttf", "otf", "sqlite", "db", "parquet");

    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SessionId sessionId;
    private final SessionDirectories directories;
    /** Entries rendered so far — the budget for filling folders up front. */
    private int rendered;

    public SessionFilesPage(SessionId sessionId, SessionDirectories directories) {
        this.sessionId = sessionId;
        this.directories = directories;
    }

    @Override
    public UiPage render() {
        UiNode body;
        List<Path> roots = directories.roots();
        if (roots.isEmpty()) {
            body = UiText.of("files-none", "This session has no directory yet. A chat gets one when it opens, "
                    + "or when a directory is chosen for it.");
        } else {
            var tree = UiTree.of("files-tree", null);
            for (Path root : roots) {
                tree.node(folder(root.toString(), "", true, 0));
            }
            tree.withCssClass("session-files");
            var scroll = UiScrollPane.of(SCROLL_ID, tree).maxHeight("65vh");
            scroll.withCssClass("session-files-scroll");
            // The tree stays in the page while a file is open, hidden by CSS, so its open folders survive.
            var content = UiStack.of(scroll, emptyViewer()).direction(UiStack.Direction.VERTICAL).gap(0);
            content.setId("files-body");
            content.withCssClass("session-files-body");
            body = content;
        }
        return UiPage.of("/admin/sessions/" + sessionId.value() + "/files", body);
    }

    /**
     * One folder's node, filled and open — what a click on a folder that was
     * not filled up front swaps in for it.
     */
    public UiPatch expanded(String root, String path) {
        UiTreeNode node = folder(root, path == null ? "" : path, true, 0);
        return UiPatch.of().patch(UiPatch.Operation.replace(node.getId(), node));
    }

    /**
     * The file open in the viewer, in the tree's place. Only the viewer is patched; CSS hides the
     * tree while the viewer has content. A hide or show patch would redraw the tree from the model
     * it was first rendered with and lose every save and delete since.
     */
    public UiPatch opened(String root, SessionDirectories.Preview preview) {
        return UiPatch.of().patch(UiPatch.Operation.replace(VIEWER_ID, viewer(root, preview)));
    }

    /** The viewer closed and the tree back, as it was left. */
    public static UiPatch closed() {
        return UiPatch.of().patch(UiPatch.Operation.replace(VIEWER_ID, emptyViewer()));
    }

    /** A saved file: its row in the tree shows the new size and time. */
    public UiPatch saved(String root, SessionDirectories.Entry entry) {
        UiTreeNode node = file(root, entry);
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(node.getId(), node))
                .toast(UiToast.success("Saved " + entry.name()));
    }

    /** A deleted file or folder: gone from the tree, and the tree shown again. */
    public static UiPatch deleted(String root, String path) {
        UiPatch patch = closed()
                .patch(UiPatch.Operation.remove(nodeId("file", root, path)))
                .patch(UiPatch.Operation.remove(nodeId("dir", root, path)));
        return patch.toast(UiToast.success("Deleted " + name(path)));
    }

    private UiTreeNode folder(String root, String path, boolean open, int depth) {
        var node = UiTreeNode.of(nodeId("dir", root, path), label(root, path)).icon("folder").open(open);
        node.labelNode(folderRow(root, path));
        if (depth > EAGER_DEPTH || rendered >= EAGER_ENTRIES) {
            return notLoaded(node, root, path);
        }
        Optional<SessionDirectories.Listing> found = directories.list(root, path);
        if (found.isEmpty()) {
            return node.content(muted(nodeId("gone", root, path), "Not found — the folder is gone."));
        }
        var listing = found.get();
        if (listing.entries().isEmpty()) {
            return node.content(muted(nodeId("empty", root, path), "(empty)"));
        }
        for (SessionDirectories.Entry entry : listing.entries()) {
            rendered++;
            node.child(entry.directory()
                    ? folder(root, entry.path(), false, depth + 1)
                    : file(root, entry));
        }
        if (listing.truncated()) {
            node.child(UiTreeNode.of(nodeId("more", root, path),
                    "… only the first " + SessionDirectories.MAX_ENTRIES + " entries are shown"));
        }
        return node;
    }

    /** A folder whose entries come when it is opened: its label and a link load them. */
    private UiTreeNode notLoaded(UiTreeNode node, String root, String path) {
        String url = base() + "/folder?root=" + encode(root) + "&path=" + encode(path);
        return node.onClick(UiTrigger.api("GET", url))
                .content(UiAction.link(nodeId("load", root, path), "Show contents").dispatch("GET", url));
    }

    /** A folder's label and, beside it, a download of the whole folder as a zip — and, below a root, delete. */
    private UiNode folderRow(String root, String path) {
        String name = path.isEmpty() ? rootName(root) : label(root, path);
        UiNode text = path.isEmpty() ? directoryLabel(root)
                : column(nodeId("dir-text", root, path), UiText.of(nodeId("dir-name", root, path), name)
                        .withCssClass("session-files-name"));
        String zip = base() + "/zip?root=" + encode(root) + "&path=" + encode(path);
        var actions = UiStack.of(UiAction.icon(nodeId("zip", root, path), "Download as zip").icon("download")
                        .download(zip, name + ".zip"))
                .direction(UiStack.Direction.HORIZONTAL).gap(4);
        if (!path.isEmpty()) {
            actions.child(deleteAction(nodeId("dir-del", root, path), root, path,
                    "Delete the folder " + name + " with everything in it? This cannot be undone."));
        }
        actions.withCssClass("session-files-actions");
        var row = UiStack.of(text, actions).direction(UiStack.Direction.HORIZONTAL).gap(8);
        row.withCssClass("session-files-row");
        return row;
    }

    /** A directory of the session: a short name, and its full path underneath in small print. */
    private UiNode directoryLabel(String root) {
        var name = UiText.of(nodeId("root-name", root, ""), rootName(root)).withCssClass("session-files-name");
        return column(nodeId("root-label", root, ""), name, muted(nodeId("root-path", root, ""), root));
    }

    private UiTreeNode file(String root, SessionDirectories.Entry entry) {
        String id = entry.path();
        String open = base() + "/view?root=" + encode(root) + "&path=" + encode(entry.path());
        var text = column(nodeId("text", root, id),
                UiText.of(nodeId("name", root, id), entry.name()).withCssClass("session-files-name"),
                muted(nodeId("meta", root, id), meta(entry)));
        var actions = UiStack.of(
                        UiAction.icon(nodeId("view", root, id), "Open").icon("show").dispatch("GET", open),
                        UiAction.icon(nodeId("dl", root, id), "Download").icon("download")
                                .download(content(root, entry.path()) + "&download=true", entry.name()),
                        deleteAction(nodeId("del", root, id), root, entry.path(),
                                "Delete " + entry.name() + "? This cannot be undone."))
                .direction(UiStack.Direction.HORIZONTAL).gap(4);
        actions.withCssClass("session-files-actions");
        var row = UiStack.of(text, actions).direction(UiStack.Direction.HORIZONTAL).gap(8);
        row.withCssClass("session-files-row");
        return UiTreeNode.of(nodeId("file", root, id), entry.name()).icon("file").labelNode(row);
    }

    /** The viewer for one file: a bar with its name and actions, then its text, a frame or a note. */
    private UiNode viewer(String root, SessionDirectories.Preview preview) {
        SessionDirectories.Entry entry = preview.entry();
        String path = entry.path();
        var title = column("files-viewer-title",
                UiText.of("files-viewer-name", entry.name()).withCssClass("session-files-name"),
                muted("files-viewer-meta", rootName(root) + "/" + path + " · " + meta(entry)));
        var close = UiAction.icon("files-viewer-close", "Back to the files").icon("back")
                .onClick(UiTrigger.patch(closed()));
        var actions = UiStack.of(
                        UiAction.icon("files-viewer-dl", "Download").icon("download")
                                .download(content(root, path) + "&download=true", entry.name()),
                        deleteAction("files-viewer-del", root, path,
                                "Delete " + entry.name() + "? This cannot be undone."))
                .direction(UiStack.Direction.HORIZONTAL).gap(4);
        actions.withCssClass("session-files-actions");
        var bar = UiStack.of(close, title, actions).direction(UiStack.Direction.HORIZONTAL).gap(8);
        bar.withCssClass("session-files-row session-files-viewer-bar");

        String ext = extension(entry.name());
        UiNode body;
        if (IMAGES.contains(ext)) {
            // An <img>, not a frame: it scales to fit, where a frame shows the image at full size.
            // The URL is form-encoded, so nothing in it can end the markdown link.
            body = UiMarkdown.of("files-viewer-image", "![image](" + content(root, path) + ")")
                    .withCssClass("session-files-image");
        } else if (ext.equals("pdf")) {
            // No sandbox: it blocks the browser's PDF viewer, and a sandboxed frame is refused by
            // X-Frame-Options SAMEORIGIN anyway. The PDF comes as application/pdf with nosniff.
            var frame = UiIFrame.of("files-viewer-frame", content(root, path)).title(entry.name()).height("65vh");
            frame.withCssClass("session-files-frame");
            body = frame;
        } else if (preview.editable() && !BINARY.contains(ext)) {
            String save = base() + "/save?root=" + encode(root) + "&path=" + encode(path);
            body = UiForm.of(EDITOR_FORM_ID, null)
                    .field(UiField.textarea("content", null, preview.text()).asEditable())
                    .action(UiAction.primary("files-editor-save", "Save").icon("save")
                            .dispatch("POST", save, EDITOR_FORM_ID))
                    .withCssClass("session-files-editor");
        } else {
            body = muted("files-viewer-none", entry.size() > SessionDirectories.MAX_EDIT_BYTES && isText(ext)
                    ? "Too large to show here (" + humanSize(entry.size()) + "). Download it to open it."
                    : "No preview for this kind of file. Download it to open it.");
        }
        var viewer = UiStack.of(bar, body).direction(UiStack.Direction.VERTICAL).gap(8);
        viewer.setId(VIEWER_ID);
        viewer.withCssClass("session-files-viewer");
        return viewer;
    }

    private static UiNode emptyViewer() {
        var viewer = UiStack.of().direction(UiStack.Direction.VERTICAL).gap(0);
        viewer.setId(VIEWER_ID);
        return viewer;
    }

    private UiAction deleteAction(String id, String root, String path, String question) {
        String url = base() + "/delete?root=" + encode(root) + "&path=" + encode(path);
        return UiAction.icon(id, "Delete").icon("delete").confirm(question).dispatch("POST", url);
    }

    private String content(String root, String path) {
        return base() + "/content?root=" + encode(root) + "&path=" + encode(path);
    }

    /** Named the way the chat's composer names it: the chat's own directory is not its session id. */
    private String rootName(String root) {
        return ai.mindconnect.chatui.ui.component.DirectoryPickerComponent.label(root, sessionId);
    }

    private static String meta(SessionDirectories.Entry entry) {
        return humanSize(entry.size()) + " · " + MODIFIED.format(entry.modified());
    }

    private static UiStack column(String id, UiNode... children) {
        var stack = UiStack.of(children).direction(UiStack.Direction.VERTICAL).gap(0);
        stack.setId(id);
        stack.withCssClass("session-files-text");
        return stack;
    }

    private static UiText muted(String id, String text) {
        return UiText.of(id, text).withCssClass("session-files-meta");
    }

    /** A folder's own name. */
    private static String label(String root, String path) {
        if (path == null || path.isEmpty()) return root;
        return name(path);
    }

    private static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Kinds that would be text if they were not too large. */
    private static boolean isText(String ext) {
        return Set.of("txt", "log", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "html", "htm", "svg",
                "py", "java", "js", "ts", "sh", "sql").contains(ext);
    }

    private String base() {
        return "/admin/api/sessions/" + sessionId.value() + "/files";
    }

    /** Stable per root and path, so a folder's reload replaces exactly its node. */
    static String nodeId(String kind, String root, String path) {
        return "files-" + kind + "-" + UUID.nameUUIDFromBytes((root + "\0" + path).getBytes(StandardCharsets.UTF_8));
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
