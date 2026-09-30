package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.service.MigrationService.FieldDiff;
import ai.mindconnect.adminui.service.MigrationService.PendingMigration;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowDataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through the encrypting file repository the admin app wires. The store
 * encrypts the api key on save, so a byte-wise diff would report every key as
 * changed forever — the service compares what the keys resolve to instead.
 */
class MigrationServiceFileRepoTest {

    private static final String NAME = "migration-field-test";
    private static final String ID = "llm-config:" + NAME;
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper("0123456789abcdef");
    /** Same id as the bundled file, so the diff is only what the test changes. */
    private static final LlmConfigId RECORD_ID = new LlmConfigId("aaaaaaaa-0000-0000-0000-000000000001");

    private LlmConfigRepository repo;
    private MigrationService service;

    private void wire(Path dir, Optional<EncryptionHelper> encryption) {
        repo = new EncryptingLlmConfigRepository(new FileLlmConfigRepository(dir, new Namespace("test")), ENCRYPTION);
        service = new MigrationService(repo,
                new InMemoryAgentDefinitionRepository(), new InMemorySkillRepository(), new InMemoryWorkflowDataRepository(),
                new ObjectMapper().registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                encryption);
    }

    private static LlmConfig stored(String model, String apiKey) {
        return new LlmConfig(RECORD_ID, NAME, LlmProvider.ANTHROPIC,
                model, "https://api.anthropic.com", apiKey,
                0.7, 8192, Map.of(), 200_000, false, null, null, null, null, null, null, null);
    }

    private PendingMigration pending() {
        return service.pending().stream().filter(p -> p.id().equals(ID)).findFirst().orElse(null);
    }

    @Test
    void anEncryptedKeyThatDecryptsToTheBundledOneIsNotADifference(@TempDir Path dir) {
        wire(dir, Optional.of(ENCRYPTION));
        repo.save(stored("new-model", "bundled-placeholder-key"));
        assertThat(repo.findByName(NAME).orElseThrow().apiKey()).startsWith("enc:");

        assertThat(pending()).as("identical once the key is decrypted").isNull();
    }

    @Test
    void aDifferentKeyIsReportedButNeverShown(@TempDir Path dir) {
        wire(dir, Optional.of(ENCRYPTION));
        repo.save(stored("new-model", "my-real-secret"));

        assertThat(pending().diffs()).containsExactly(
                new FieldDiff("apiKey", "(encrypted)", "••••••••"));
    }

    @Test
    void applyingTheKeyFieldMakesItDisappearFromTheDiff(@TempDir Path dir) {
        wire(dir, Optional.of(ENCRYPTION));
        repo.save(stored("new-model", "my-real-secret"));

        assertThat(service.applyField(ID, "apiKey")).isTrue();

        // Re-encrypted on save — but it decrypts to the bundled key now.
        assertThat(repo.findByName(NAME).orElseThrow().apiKey()).startsWith("enc:");
        assertThat(pending()).isNull();
    }

    @Test
    void withoutTheHelperAnEncryptedKeyStaysADifference(@TempDir Path dir) {
        wire(dir, Optional.empty());
        repo.save(stored("new-model", "bundled-placeholder-key"));

        assertThat(pending().diffs()).extracting(FieldDiff::field).containsExactly("apiKey");
    }

    @Test
    void applyingOneFieldThroughTheFileRepoKeepsTheEncryptedKey(@TempDir Path dir) {
        wire(dir, Optional.of(ENCRYPTION));
        repo.save(stored("old-model", "my-real-secret"));
        String encryptedKey = repo.findByName(NAME).orElseThrow().apiKey();
        assertThat(encryptedKey).startsWith("enc:");

        assertThat(service.applyField(ID, "model")).isTrue();

        LlmConfig after = repo.findByName(NAME).orElseThrow();
        assertThat(after.model()).isEqualTo("new-model");
        assertThat(after.apiKey()).isEqualTo(encryptedKey);
        assertThat(pending().diffs()).extracting(FieldDiff::field).containsExactly("apiKey");
        assertThat(repo.findAll()).hasSize(1);
    }
}
