package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agentrest.service.VectorStoreService.Hit;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Search hits as a table: score, provenance, every metadata key, text. */
public final class VectorStoreHitsComponent implements UiComponent {

    public static final String ID = "vs-search-result";

    private final List<Hit> hits;

    public VectorStoreHitsComponent(List<Hit> hits) {
        this.hits = hits;
    }

    /** What stands in the table's place when the search itself failed. */
    public static UiNode failed(String reason) {
        return UiText.of(ID, "Search failed: " + reason).withCssClass("task-card-body");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UiNode render() {
        if (hits.isEmpty()) {
            return UiText.of(ID, "No results (store empty, or all hits below Min Score).");
        }
        // Metadata keys vary per chunk — collect the union for the columns.
        Set<String> metaKeys = new TreeSet<>();
        hits.forEach(h -> metaKeys.addAll(h.metadata().keySet()));

        UiTable table = UiTable.of(ID, "Results (" + hits.size() + ")").stackOnMobile(true)
                .column(UiTable.Column.text("rank", "#"))
                .column(UiTable.Column.text("score", "Score"))
                .column(UiTable.Column.text("chunk", "Chunk"));
        for (String key : metaKeys) {
            table.column(UiTable.Column.text("m-" + key, capitalize(key)));
        }
        table.column(UiTable.Column.text("text", "Text"));

        int rank = 1;
        for (Hit hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", hit.entityId() + "#" + hit.chunkId());
            row.put("rank", String.valueOf(rank++));
            row.put("score", String.format("%.3f", hit.score()));
            row.put("chunk", hit.metadata().getOrDefault("file", hit.entityId()) + " #" + hit.ordinal());
            for (String key : metaKeys) {
                row.put("m-" + key, hit.metadata().getOrDefault(key, ""));
            }
            row.put("text", firstChars(hit.text()));
            table.row(row);
        }
        return table;
    }

    private static String capitalize(String key) {
        return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private static String firstChars(String text) {
        String stripped = text.strip().replace('\n', ' ');
        return stripped.length() > 160 ? stripped.substring(0, 160) + "…" : stripped;
    }
}
