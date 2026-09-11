package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.WorkingDirBrowser;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.nio.file.Path;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The chat's directory dialog — a folder chooser, the way the operating
 * system's works: one path, a list of folders to step into, and one button
 * that takes the folder you are in. The path field is the one state: a
 * folder clicked below lands in it, a path typed into it can be opened
 * below, and <i>Use this folder</i> takes whatever it says — empty for the
 * server's default directory.
 *
 * <p>Additional directories are a second, smaller list under the folders:
 * the ones the chat reaches beside its working directory, each removable,
 * and the folder in the field can be added as one more.
 *
 * <p>The tree is the {@link WorkingDirBrowser}'s: what the working-dir
 * policy allows and nothing beyond. Stable id {@link #ID}: browsing
 * replaces the form in place, choosing closes the dialog.
 */
public final class DirectoryPickerComponent {

    /** The form's id — browsing redraws the form under it. */
    public static final String ID = "chat-dir-picker";

    /** The field the chosen path travels in. */
    public static final String PATH_FIELD = "path";

    private DirectoryPickerComponent() {}

    /**
     * The dialog's form, showing {@code listing} — one level of the tree —
     * with its directory in the path field.
     */
    public static UiForm form(SessionId sessionId, AgentSession session, WorkingDirBrowser.Listing listing) {
        var form = UiForm.of(ID, null)
                .field(UiField.text(PATH_FIELD, "Working directory", listing.current())
                        .asEditable()
                        .placeholder("The server's default directory")
                        .hint("Type a path, or step into a folder below. Relative paths, bash "
                                + "and the model's sense of where it is all mean this directory; "
                                + "empty means the server's default.")
                        .trailing(UiAction.secondary("go", "Open").icon("folder-open")
                                .onClick(trigger(on(ChatUiController.class).goDir(sessionId, null, null), ID))))
                .content(UiStack.of(ID + "-lists").gap(12)
                        .child(folders(sessionId, listing))
                        .child(additional(sessionId, session)))
                .action(UiAction.primary("use", "Use this folder").icon("check")
                        .onClick(trigger(on(ChatUiController.class).useDir(sessionId, null, null), ID)))
                .action(UiAction.secondary("cancel", "Cancel")
                        .onClick(trigger(on(ChatUiController.class).closeDialog())));
        return form;
    }

    /** The folders inside the listed directory, each a step down; a step up when there is one. */
    private static UiNode folders(SessionId sessionId, WorkingDirBrowser.Listing listing) {
        var list = UiList.of(ID + "-folders", "Folders in " + name(listing.current())).icon("folder");
        if (listing.parent() != null) {
            list.action(UiAction.secondary("up", "Up").icon("corner-left-up")
                    .onClick(trigger(on(ChatUiController.class).browseDirs(sessionId, listing.parent(), null))));
        }
        for (String dir : listing.subdirs()) {
            list.item(UiList.Item.of(ID + "-f-" + itemId(dir), name(dir)).icon("folder")
                    .onClick(trigger(on(ChatUiController.class).browseDirs(sessionId, dir, null))));
        }
        if (listing.subdirs().isEmpty()) {
            list.item(UiList.Item.of(ID + "-f-none", "No folders inside"));
        }
        var stack = UiStack.of(ID + "-tree").gap(4).child(list);
        if (listing.truncated()) {
            stack.child(UiText.of(ID + "-more", "Only the first " + WorkingDirBrowser.MAX_ENTRIES
                    + " folders are shown — type the path instead."));
        }
        if (listing.root() != null) {
            stack.child(UiText.of(ID + "-root", "Anything under " + listing.root() + " goes."));
        }
        return stack;
    }

    /** The directories the chat reaches beside its working one, each removable, plus a way to add the field's. */
    private static UiNode additional(SessionId sessionId, AgentSession session) {
        var list = UiList.of(ID + "-extra", "Additional directories").icon("folder-plus")
                .action(UiAction.secondary("add", "Add this folder").icon("add")
                        .onClick(trigger(on(ChatUiController.class).addDir(sessionId, null, null), ID)));
        for (String dir : session.additionalDirs()) {
            list.item(UiList.Item.of(ID + "-x-" + itemId(dir), dir).icon("folder")
                    .action(UiAction.danger("remove", "Remove").icon("remove")
                            .onClick(trigger(on(ChatUiController.class).removeDir(sessionId, dir, null)))));
        }
        if (session.additionalDirs().isEmpty()) {
            list.item(UiList.Item.of(ID + "-x-none", "None — reachable by absolute path beside the working "
                    + "directory: a library checked out next to the project, a data folder."));
        }
        return list;
    }

    /** A path as a DOM id: URL-encoded, so it is unique and free of slashes and spaces. */
    private static String itemId(String dir) {
        return java.net.URLEncoder.encode(dir, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The last segment of a path — the folder's own name; the path itself at a root. */
    static String name(String dir) {
        Path file = Path.of(dir).getFileName();
        return file == null ? dir : file.toString();
    }
}
