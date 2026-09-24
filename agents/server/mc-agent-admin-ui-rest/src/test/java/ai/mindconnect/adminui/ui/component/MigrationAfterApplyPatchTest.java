package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.MigrationService.EntityType;
import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.adminui.service.MigrationService.Status;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiPatch.Op;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * An apply answers with a patch, not the page: the applied item leaves the
 * list in place and only the counts around it are rewritten.
 */
class MigrationAfterApplyPatchTest {

    private static final PendingMigration SCOUT =
            new PendingMigration(EntityType.AGENT, "Scout", Status.NEW, List.of());
    private static final PendingMigration RANGER =
            new PendingMigration(EntityType.AGENT, "Ranger", Status.NEW, List.of());
    private static final PendingMigration DEFAULT_LLM =
            new PendingMigration(EntityType.LLM_CONFIG, "default", Status.CHANGED, List.of(
                    new FieldDiff("model", "old-model", "new-model"),
                    new FieldDiff("apiKey", "my-real-secret", "bundled-placeholder-key")));

    private static UiPatch afterApply(PendingMigration applied, PendingMigration... stillPending) {
        return new MigrationListComponent(List.of(stillPending)).afterApply(applied, "Applied");
    }

    @Test
    void theAppliedItemIsRemovedAndItsSectionCountFollows() {
        UiPatch patch = afterApply(SCOUT, RANGER, DEFAULT_LLM);

        assertThat(patch.getPatches()).extracting(UiPatch.Operation::getOp, UiPatch.Operation::getTargetId)
                .containsExactly(
                        tuple(Op.REMOVE, "agent:Scout"),
                        tuple(Op.REPLACE, "migration-group-agent-sum"),
                        tuple(Op.REPLACE, "apply-all"));
        assertThat(((UiAction) patch.getPatches().get(2).getNode()).getLabel()).isEqualTo("Apply all (2)");
        assertThat(patch.getToasts()).hasSize(1);
    }

    @Test
    void theLastItemOfATypeTakesItsSectionWithIt() {
        UiPatch patch = afterApply(SCOUT, DEFAULT_LLM);

        assertThat(patch.getPatches()).extracting(UiPatch.Operation::getOp, UiPatch.Operation::getTargetId)
                .containsExactly(
                        tuple(Op.REMOVE, "migration-group-agent"),
                        tuple(Op.REPLACE, "apply-all"));
    }

    @Test
    void nothingLeftRendersTheUpToDateList() {
        UiPatch patch = afterApply(SCOUT);

        assertThat(patch.getPatches()).extracting(UiPatch.Operation::getOp, UiPatch.Operation::getTargetId)
                .containsExactly(tuple(Op.REPLACE, "migration-list"));
    }

    @Test
    void aPartlyAppliedRecordKeepsItsItemWithTheRemainingDiff() {
        var rest = new PendingMigration(EntityType.LLM_CONFIG, "default", Status.CHANGED, List.of(
                new FieldDiff("apiKey", "my-real-secret", "bundled-placeholder-key")));

        UiPatch patch = afterApply(DEFAULT_LLM, rest, SCOUT);

        assertThat(patch.getPatches()).extracting(UiPatch.Operation::getOp, UiPatch.Operation::getTargetId)
                .containsExactly(
                        tuple(Op.REPLACE, "migration-diff-llm-config:default"),
                        tuple(Op.REPLACE, "llm-config:default-sum"),
                        tuple(Op.REPLACE, "apply-all"));
    }
}
