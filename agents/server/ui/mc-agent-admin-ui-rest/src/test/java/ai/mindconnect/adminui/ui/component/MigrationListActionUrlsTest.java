package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.MigrationService.EntityType;
import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.service.MigrationService.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the routes the migration list dispatches to: the per-item Apply, the
 * per-field Apply on every diff row (the client fills {@code {id}} with the
 * row id, which is the field name), and Apply all.
 */
class MigrationListActionUrlsTest {

    private static PendingMigration changed() {
        return new PendingMigration(EntityType.LLM_CONFIG, "default", Status.CHANGED, List.of(
                new FieldDiff("model", "old-model", "new-model"),
                new FieldDiff("apiKey", "my-real-secret", "bundled-placeholder-key")));
    }

    private static String renderedJson(PendingMigration... pending) throws Exception {
        return new ObjectMapper()
                .writeValueAsString(new MigrationListComponent(List.of(pending)).render());
    }

    @Test
    void everyDiffRowCarriesItsOwnApply() throws Exception {
        String json = renderedJson(changed());

        assertThat(json).contains(
                "\"url\":\"/admin/api/migrations/apply-field?id=llm-config%3Adefault&field={id}\"");
        // The row id is what {id} becomes — the field name.
        assertThat(json).contains("\"id\":\"model\"");
        assertThat(json).contains("\"id\":\"apiKey\"");
    }

    @Test
    void wholeRecordAndApplyAllKeepTheirRoutes() throws Exception {
        String json = renderedJson(changed());

        assertThat(json).contains("\"url\":\"/admin/api/migrations/apply?id=llm-config%3Adefault\"");
        assertThat(json).contains("\"url\":\"/admin/api/migrations/apply-all\"");
    }

    @Test
    void aNewRecordHasNoDiffTableAndSoNoPerFieldApply() throws Exception {
        String json = renderedJson(
                new PendingMigration(EntityType.AGENT, "Scout", Status.NEW, List.of()));

        assertThat(json).doesNotContain("apply-field");
        assertThat(json).contains("\"url\":\"/admin/api/migrations/apply?id=agent%3AScout\"");
    }
}
