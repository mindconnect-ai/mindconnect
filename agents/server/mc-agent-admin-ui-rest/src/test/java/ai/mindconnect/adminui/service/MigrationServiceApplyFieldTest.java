package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowDataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applying a single field takes just that value from the bundle and leaves
 * the rest of the stored record alone. The case that motivated it: a bundled
 * LLM config changes its model, and applying the whole record would overwrite
 * the API key the admin entered — the one thing the bundle cannot know.
 *
 * <p>The bundled side is {@code src/test/resources/initial-data/llm-configs/
 * migration-field-test.json}.
 */
class MigrationServiceApplyFieldTest {

    private static final String NAME = "migration-field-test";
    private static final String ID = "llm-config:" + NAME;
    /** Same id as the bundled file, so the diff is only what the test changes. */
    private static final LlmConfigId RECORD_ID = new LlmConfigId("aaaaaaaa-0000-0000-0000-000000000001");

    private InMemoryLlmConfigRepository llmConfigs;
    private MigrationService service;

    @BeforeEach
    void setUp() {
        llmConfigs = new InMemoryLlmConfigRepository();
        service = new MigrationService(
                llmConfigs,
                new InMemoryAgentDefinitionRepository(),
                new InMemorySkillRepository(),
                new InMemoryWorkflowDataRepository(),
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                Optional.empty());
    }

    private LlmConfig stored() {
        return new LlmConfig(RECORD_ID, NAME, LlmProvider.ANTHROPIC,
                "old-model", "https://api.anthropic.com", "my-real-secret",
                0.7, 8192, Map.of(), 200_000, false, null, null, null, null, null, null, null);
    }

    @Test
    void theStoredRecordShowsUpAsChangedWithBothFieldsInTheDiff() {
        llmConfigs.save(stored());

        PendingMigration pending = service.pending().stream()
                .filter(p -> p.id().equals(ID)).findFirst().orElseThrow();

        assertThat(pending.status()).isEqualTo(MigrationService.Status.CHANGED);
        assertThat(pending.diffs()).extracting(FieldDiff::field)
                .containsExactly("model", "apiKey");
    }

    @Test
    void applyingOneFieldKeepsTheApiKey() {
        llmConfigs.save(stored());

        assertThat(service.applyField(ID, "model")).isTrue();

        LlmConfig after = llmConfigs.findByName(NAME).orElseThrow();
        assertThat(after.model()).isEqualTo("new-model");
        assertThat(after.apiKey()).isEqualTo("my-real-secret");

        // Only the api key is left to reconcile now.
        PendingMigration pending = service.pending().stream()
                .filter(p -> p.id().equals(ID)).findFirst().orElseThrow();
        assertThat(pending.diffs()).extracting(FieldDiff::field)
                .containsExactly("apiKey");
    }

    @Test
    void applyingTheWholeRecordStillOverwritesEverything() {
        llmConfigs.save(stored());

        assertThat(service.apply(ID)).isTrue();

        LlmConfig after = llmConfigs.findByName(NAME).orElseThrow();
        assertThat(after.model()).isEqualTo("new-model");
        assertThat(after.apiKey()).isEqualTo("bundled-placeholder-key");
    }

    @Test
    void aNewRecordHasNothingToMergeInto() {
        // nothing stored under NAME
        assertThat(service.applyField(ID, "model")).isFalse();
        assertThat(llmConfigs.findByName(NAME)).isEmpty();
    }

    @Test
    void aFieldNeitherSideHasIsNotApplied() {
        llmConfigs.save(stored());

        assertThat(service.applyField(ID, "(structural difference)")).isFalse();
        assertThat(llmConfigs.findByName(NAME).orElseThrow().model()).isEqualTo("old-model");
    }

    @Test
    void aFieldTheBundleDroppedIsRemoved() {
        llmConfigs.save(stored());
        // The bundle has no "delegatesTo"; stored has one. Applying that field
        // takes the bundle's absence.
        llmConfigs.save(new LlmConfig(RECORD_ID, NAME, LlmProvider.ANTHROPIC,
                "old-model", "https://api.anthropic.com", "my-real-secret",
                0.7, 8192, Map.of(), 200_000, false, "somewhere-else", null, null, null, null, null, null));

        assertThat(service.applyField(ID, "delegatesTo")).isTrue();

        LlmConfig after = llmConfigs.findByName(NAME).orElseThrow();
        assertThat(after.delegatesTo()).isNull();
        assertThat(after.apiKey()).isEqualTo("my-real-secret");
    }
}
