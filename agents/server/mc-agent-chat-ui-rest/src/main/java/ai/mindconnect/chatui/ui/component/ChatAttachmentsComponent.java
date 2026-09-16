package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.chatui.ui.controller.ChatFilesUiController;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ai.mindconnect.agent.SessionId;

/**
 * The chat page's attached-files panel: file name, what it is, how it
 * reaches the model, and a Remove action per attachment. Pure rendering —
 * the files are the session's record
 * ({@link ai.mindconnect.agentrest.service.SessionFileService#attachments}),
 * the chunk counts come from the session's vector store
 * ({@link ai.mindconnect.agentrest.service.SessionFileService#listAttachments}).
 */
public final class ChatAttachmentsComponent {

    private ChatAttachmentsComponent() {}

    /**
     * Which of the chat's files a panel lists. The "+" menu keeps pictures and
     * documents apart — Add images and Upload files — and each dialog shows
     * only its own, so each kind is its own panel with its own stable id.
     */
    public enum Kind {
        /** Everything that is not a picture: PDFs, text, office files. */
        DOCUMENTS("chat-attachments", "Documents"),
        /** Pictures only. */
        IMAGES("chat-attachments-images", "Images");

        private final String panelId;
        private final String title;

        Kind(String panelId, String title) {
            this.panelId = panelId;
            this.title = title;
        }

        /** The panel's node id — the target attach and remove patches replace. */
        public String panelId() {
            return panelId;
        }

        /** Whether a file belongs in this panel. */
        public boolean holds(AttachedFile file) {
            return (this == IMAGES) == file.isImage();
        }

        /** The files of this kind, in attach order. */
        public List<AttachedFile> of(List<AttachedFile> files) {
            return files.stream().filter(this::holds).toList();
        }
    }

    /**
     * The panel for one kind of file, under that kind's stable id so
     * attach/delete patches always have a target.
     *
     * @param files  the session's attached files, in attach order — all of them; the kind picks its own
     * @param chunks ingested file id → searchable chunks, for the files that were indexed
     */
    public static UiNode node(SessionId sessionId, Kind kind, List<AttachedFile> files, Map<String, Long> chunks) {
        var panel = UiStack.of(kind.panelId()).gap(4);
        files = kind.of(files);
        if (files.isEmpty()) {
            return panel;
        }
        Map<String, Long> chunksByName = new HashMap<>();
        chunks.forEach((ingestedId, count) ->
                chunksByName.merge(Path.of(ingestedId).getFileName().toString(), count, Long::sum));
        var table = UiTable.of(kind.panelId() + "-table", kind.title + " (" + files.size() + ")")
                .column(UiTable.Column.text("file", "File"))
                .column(UiTable.Column.text("kind", "Kind"))
                .column(UiTable.Column.text("reach", "Reaches the model as"))
                .rowAction(UiAction.danger("remove", "Remove").icon("remove")
                        .confirm("Remove this file from the conversation?")
                        .onClick(trigger(on(ChatFilesUiController.class)
                                .remove(sessionId.value(), ROW_ID.toString(), null))));
        for (AttachedFile f : files) {
            table.row(Map.of(
                    "id", java.net.URLEncoder.encode(f.name(), java.nio.charset.StandardCharsets.UTF_8),
                    "file", f.name(),
                    "kind", kind(f),
                    "reach", reach(f, chunksByName.getOrDefault(f.name(), 0L))));
        }
        panel.child(table);
        return panel;
    }

    private static String kind(AttachedFile f) {
        if (f.isImage()) return "image";
        if (f.isPdf()) return "PDF";
        return f.mediaType() != null && !f.mediaType().isBlank() ? f.mediaType() : "file";
    }

    /** How the file reaches the model — with the next message, or through vector_search. */
    private static String reach(AttachedFile f, long chunks) {
        if (f.isImage()) return "image with the next message";
        String search = chunks + (chunks == 1 ? " searchable chunk" : " searchable chunks");
        return f.isPdf() ? "document with the next message · " + search : search;
    }
}
