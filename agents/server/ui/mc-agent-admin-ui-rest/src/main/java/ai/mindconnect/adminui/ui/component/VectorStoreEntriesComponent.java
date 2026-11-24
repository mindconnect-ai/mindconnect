package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agentrest.service.VectorStoreContents.NamedEntry;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.common.Page;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * What a store lists, filtered by a search (name, id or type), one page of
 * it. The search field and the page buttons fetch just this table again.
 */
public final class VectorStoreEntriesComponent implements UiComponent {

    public static final String ID = "vs-entries";
    private static final String BASE = "/admin/vector-stores";

    private final String store;
    private final String query;
    private final Page<NamedEntry> found;

    public VectorStoreEntriesComponent(String store, String query, Page<NamedEntry> found) {
        this.store = store;
        this.query = query;
        this.found = found;
    }

    /** What stands in the table's place when the store cannot list its entries. */
    public static UiNode unavailable(String reason) {
        return UiText.of(ID, "The entries cannot be listed: " + reason).withCssClass("task-card-body");
    }

    /** {@code &key=value}, URL-encoded; nothing for an empty value. */
    public static String param(String key, String value) {
        return value == null || value.isBlank() ? ""
                : "&" + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UiTable render() {
        String enc = URLEncoder.encode(store, StandardCharsets.UTF_8);
        UiForm searchForm = UiForm.of("vs-entries-search", null);
        searchForm.field(UiField.text("q", "", query).asEditable().icon("search")
                .placeholder("Search name, id or type…")
                .onChange(UiTrigger.api("POST", BASE + "/stores/" + enc + "/entries/search", "vs-entries-search")));
        UiTable table = UiTable.of(ID, null).stackOnMobile(true)
                .headerExtra(searchForm)
                .column(UiTable.Column.text("file", "Entry"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("chunks", "Chunks"))
                .rowAction(UiAction.secondary("chunks", "Chunks").icon("list")
                        .onClick(UiTrigger.go(BASE + "/stores/" + enc + "?tab=chunks&entry={id}")))
                .rowAction(UiAction.danger("delete-file", "Remove").icon("delete")
                        .confirm("Take this entry off the store? A document goes with it; a stored file "
                                + "stays searchable in the other stores and chats that list it.")
                        // Ids may contain slashes (paths of documents from before 0.9) — they
                        // travel URL-encoded in the query, never in the path.
                        .dispatch("DELETE", BASE + "/stores/" + enc + "/files?file={id}"));
        for (NamedEntry named : found.items()) {
            table.row(Map.of(
                    "id", URLEncoder.encode(named.entry().entityId(), StandardCharsets.UTF_8),
                    "file", named.name(),
                    "type", named.entry().entityType(),
                    "chunks", String.valueOf(named.entry().chunks())));
        }
        table.paginate(found.page(), found.size(), (int) found.total(), UiTrigger.api("GET",
                BASE + "/stores/" + enc + "/entries/table?page={page}" + param("q", query)));
        return table;
    }
}
