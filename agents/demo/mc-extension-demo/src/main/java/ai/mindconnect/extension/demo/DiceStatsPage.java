package ai.mindconnect.extension.demo;

import ai.mindconnect.ui.ext.chart.UiChart;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The statistics screen: a bar chart of how often each face came up, per
 * day, and a table naming the day's most frequent face. The chart is the
 * {@code chart} node of {@code mc-semantic-ui-ext-chart}; its renderer
 * reaches the page through the asset registry, nothing to wire.
 */
final class DiceStatsPage {

    static final String NAVIGATE = "/admin/demo-dungeon/stats";

    private DiceStatsPage() {
    }

    static UiPage render(DiceStats stats, int days) {
        UiStack stack = UiStack.of("dice-stats");
        stack.child(UiText.of("dice-stats-intro",
                "Every die the dungeon demo rolled with `demo_dice`, kept in the extension's own store per namespace, "
                        + "over the last " + days + " days."));
        if (stats.isEmpty()) {
            stack.child(UiText.of("dice-stats-none", "No rolls yet — start an adventure and roll."));
            return UiPage.of(NAVIGATE, stack);
        }
        List<LocalDate> dayList = new ArrayList<>(stats.byDay().keySet());
        List<String> labels = dayList.stream().map(LocalDate::toString).toList();
        var data = new UiChart.ChartData();
        data.setLabels(labels);
        List<UiChart.ChartData.Series> series = new ArrayList<>();
        for (int face : stats.faces()) {
            var s = new UiChart.ChartData.Series();
            s.setName("Face " + face);
            List<Number> values = new ArrayList<>();
            for (LocalDate day : dayList) values.add(stats.count(day, face));
            s.setValues(values);
            series.add(s);
        }
        data.setSeries(series);
        stack.child(UiChart.of("dice-stats-chart", "Rolls per face and day", UiChart.ChartType.BAR, data));

        UiTable table = UiTable.of("dice-stats-table", "Most frequent face per day").stackOnMobile(true)
                .column(UiColumn.text("day", "Day"))
                .column(UiColumn.number("rolls", "Rolls"))
                .column(UiColumn.text("top", "Most frequent face"))
                .column(UiColumn.number("times", "Times"));
        for (LocalDate day : dayList) {
            var top = stats.topFace(day).orElseThrow();
            table.row(Map.of("id", day.toString(), "day", day.toString(), "rolls", stats.total(day),
                    "top", Integer.toString(top.getKey()), "times", top.getValue()));
        }
        stack.child(table);
        return UiPage.of(NAVIGATE, stack);
    }
}
