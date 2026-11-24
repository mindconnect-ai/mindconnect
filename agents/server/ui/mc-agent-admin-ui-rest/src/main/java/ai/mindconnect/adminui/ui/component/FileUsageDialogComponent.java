package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agentrest.service.FileUsageService.FileUsage;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;

import java.util.List;
import java.util.Map;

/**
 * A dialog over the files tab: the file's chats (each opens the chat) and
 * stores (each opens the store).
 */
public final class FileUsageDialogComponent implements UiComponent {

    public static final String ID = "vs-file-usage-dialog";
    private static final String BASE = "/admin/vector-stores";

    private final StoredFile file;
    private final FileUsage usages;

    public FileUsageDialogComponent(StoredFile file, FileUsage usages) {
        this.file = file;
        this.usages = usages;
    }

    public static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UiDialog render() {
        String id = file.id().value();
        UiStack body = UiStack.of("vs-file-usage").gap(16);
        body.child(UiText.of("vs-file-usage-meta", id + " · " + readableSize(file.size())
                + (file.contentType() == null ? "" : " · " + file.contentType())).withCssClass("wf-run-meta"));

        List<ManagedSession> chatsOfFile = usages.chatsOf(id);
        UiTable chats = UiTable.of("vs-file-chats", "Chats (" + chatsOfFile.size() + ")").stackOnMobile(true)
                .column(UiTable.Column.text("title", "Chat"))
                .column(UiTable.Column.text("started", "Started"))
                .rowAction(UiAction.secondary("open", "Open").icon("show").onClick(UiTrigger.go("/chat/sessions/{id}")));
        for (ManagedSession chat : chatsOfFile) {
            chats.row(Map.of(
                    "id", chat.id().value(),
                    "title", chat.title() == null || chat.title().isBlank()
                            ? "Chat " + chat.id().value().substring(0, Math.min(8, chat.id().value().length()))
                            : chat.title(),
                    "started", chat.createdAt() == null ? ""
                            : chat.createdAt().toString().replace("T", " ").substring(0, 16)));
        }
        body.child(chatsOfFile.isEmpty() ? UiText.of("vs-file-no-chats", "Not attached to any of your chats.") : chats);

        List<VectorStoreInstance> storesOfFile = usages.storesOf(id);
        UiTable storeTable = UiTable.of("vs-file-stores", "Vector stores (" + storesOfFile.size() + ")")
                .stackOnMobile(true)
                .column(UiTable.Column.text("name", "Store"))
                .column(UiTable.Column.text("template", "Template"))
                .column(UiTable.Column.text("scope", "Scope"))
                .rowAction(UiAction.secondary("view", "View").icon("show").onClick(UiTrigger.go(BASE + "/stores/{id}")));
        for (VectorStoreInstance instance : storesOfFile) {
            storeTable.row(Map.of(
                    "id", instance.name(),
                    "name", instance.name(),
                    "template", instance.templateName() == null ? "" : instance.templateName(),
                    "scope", instance.scope().toString()));
        }
        body.child(storesOfFile.isEmpty() ? UiText.of("vs-file-no-stores", "Not listed in any vector store.")
                : storeTable);

        UiDialog dialog = UiDialog.of("Used in: " + file.name(), null, body);
        dialog.setId(ID);
        return dialog;
    }
}
