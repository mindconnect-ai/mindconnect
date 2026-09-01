package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.chatui.ui.controller.ChatFilesUiController;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * The chat page's attached-files panel: file name, chunk count and a Remove
 * action per attachment. Pure rendering — the data (file id → chunk count)
 * comes from {@link ai.mindconnect.agentrest.service.SessionFileService#listAttachments},
 * the domain half that also serves the REST endpoint.
 */
public final class ChatAttachmentsComponent {

    private ChatAttachmentsComponent() {}

    /** Stable id {@code chat-attachments} so attach/delete patches always have a target. */
    public static UiNode node(UUID sessionId, Map<String, Long> files) {
        var panel = UiStack.of("chat-attachments").gap(4);
        if (files.isEmpty()) {
            return panel;
        }
        var table = UiTable.of("chat-attachments-table", "Attached Files (" + files.size() + ")")
                .column(UiTable.Column.text("file", "File"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.danger("remove", "Remove").icon("remove")
                        .confirm("Remove this file from the conversation? The agent can no longer search it.")
                        .onClick(trigger(on(ChatFilesUiController.class)
                                .remove(sessionId, ROW_ID.toString()))));
        files.forEach((fileId, count) -> table.row(Map.of(
                "id", java.net.URLEncoder.encode(fileId, java.nio.charset.StandardCharsets.UTF_8),
                "file", Path.of(fileId).getFileName().toString(),
                "chunks", String.valueOf(count))));
        panel.child(table);
        return panel;
    }
}
