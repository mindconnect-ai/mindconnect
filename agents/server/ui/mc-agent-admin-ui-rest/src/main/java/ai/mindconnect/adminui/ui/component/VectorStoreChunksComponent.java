package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agentrest.service.VectorStoreContents.Chunk;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.common.Page;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The chunks of a store's entries — of one entry when one is chosen — whose
 * text contains the search, one page of them, as the index has them.
 */
public final class VectorStoreChunksComponent implements UiComponent {

    public static final String ID = "vs-chunks";
    private static final String BASE = "/admin/vector-stores";

    private final String store;
    private final String query;
    private final String selected;
    private final Map<String, String> entryNames;
    private final Page<Chunk> found;

    /**
     * @param selected   the id of the entry the chunks are limited to; {@code null} for all
     * @param entryNames the names of the store's entries by id, in the order the choice lists them
     */
    public VectorStoreChunksComponent(String store, String query, String selected, Map<String, String> entryNames,
                                      Page<Chunk> found) {
        this.store = store;
        this.query = query;
        this.selected = selected;
        this.entryNames = entryNames;
        this.found = found;
    }

    /** What stands in the table's place when the store cannot list its chunks. */
    public static UiNode unavailable(String reason) {
        return UiText.of(ID, "The chunks cannot be listed: " + reason).withCssClass("task-card-body");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UiTable render() {
        String enc = URLEncoder.encode(store, StandardCharsets.UTF_8);
        List<UiField.Option> entries = new ArrayList<>();
        entries.add(UiField.Option.of("", "All entries"));
        entryNames.forEach((id, label) -> entries.add(UiField.Option.of(id, label)));

        UiForm searchForm = UiForm.of("vs-chunks-search", null);
        var searchTrigger = UiTrigger.api("POST", BASE + "/stores/" + enc + "/chunks/search", "vs-chunks-search");
        searchForm.field(UiField.select("entry", "", selected == null ? "" : selected, entries).asEditable()
                .onChange(searchTrigger));
        searchForm.field(UiField.text("q", "", query).asEditable().icon("search")
                .placeholder("Search the chunks' text…").onChange(searchTrigger));
        UiTable table = UiTable.of(ID, null).stackOnMobile(true)
                .headerExtra(searchForm)
                .column(UiTable.Column.text("entry", "Entry"))
                .column(UiTable.Column.number("ordinal", "#"))
                .column(UiTable.Column.text("meta", "Metadata"))
                .column(UiTable.Column.text("text", "Text"));
        for (Chunk chunk : found.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", chunk.entityType() + ":" + chunk.entityId() + "#" + chunk.chunkId());
            row.put("entry", chunk.entryName());
            row.put("ordinal", String.valueOf(chunk.ordinal()));
            row.put("meta", chunk.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(" · ")));
            row.put("text", chunk.text());
            table.row(row);
        }
        table.paginate(found.page(), found.size(), found.total(), UiTrigger.api("GET",
                BASE + "/stores/" + enc + "/chunks/table?page={page}"
                        + VectorStoreEntriesComponent.param("q", query)
                        + VectorStoreEntriesComponent.param("entry", selected)));
        return table;
    }
}
