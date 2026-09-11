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
import java.util.UUID;

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
     * Stable id {@code chat-attachments} so attach/delete patches always have a target.
     *
     * @param files  the session's attached files, in attach order
     * @param chunks ingested file id → searchable chunks, for the files that were indexed
     */
    public static UiNode node(UUID sessionId, List<AttachedFile> files, Map<String, Long> chunks) {
        var panel = UiStack.of("chat-attachments").gap(4);
        if (files.isEmpty()) {
            return panel;
        }
        Map<String, Long> chunksByName = new HashMap<>();
        chunks.forEach((ingestedId, count) ->
                chunksByName.merge(Path.of(ingestedId).getFileName().toString(), count, Long::sum));
        var table = UiTable.of("chat-attachments-table", "Attached Files (" + files.size() + ")")
                .column(UiTable.Column.text("file", "File"))
                .column(UiTable.Column.text("kind", "Kind"))
                .column(UiTable.Column.text("reach", "Reaches the model as"))
                .rowAction(UiAction.danger("remove", "Remove").icon("remove")
                        .confirm("Remove this file from the conversation?")
                        .onClick(trigger(on(ChatFilesUiController.class)
                                .remove(sessionId, ROW_ID.toString()))));
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
