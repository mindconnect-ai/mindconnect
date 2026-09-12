package ai.mindconnect.agent.tools.workflow.registry;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRegistryInstallerTest {

    /** A workflow as the store writes it — the type id is part of the format. */
    private static final String JSON = """
            {
              "@class" : "ai.mindconnect.workflow.domain.WorkflowData",
              "name" : "summarize",
              "steps" : [ ],
              "subWorkflows" : [ ]
            }
            """;

    private static RegistryEntry entry(String name) {
        return new RegistryEntry("summarize", RegistryItemType.WORKFLOW, name, null, "1.0",
                "workflows/summarize.json", List.of(), null, null, List.of());
    }

    private InMemoryWorkflowDataRepository repository;
    private WorkflowRegistryInstaller installer;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWorkflowDataRepository();
        installer = new WorkflowRegistryInstaller(repository);
    }

    @Test
    void installs_the_workflow_under_the_entrys_name_as_its_id() {
        ImportedItem item = installer.install(entry("summarize"), JSON, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(item.name()).isEqualTo("summarize");
        assertThat(repository.findById("summarize")).isPresent();
    }

    @Test
    void keeps_what_is_here_unless_the_mode_says_otherwise() {
        repository.save("summarize", new WorkflowData());

        assertThat(installer.install(entry("summarize"), JSON, ImportMode.SKIP_EXISTING).status())
                .isEqualTo(ImportStatus.SKIPPED);
        assertThat(installer.install(entry("summarize"), JSON, ImportMode.OVERWRITE).status())
                .isEqualTo(ImportStatus.UPDATED);
    }

    @Test
    void exists_asks_about_the_same_id_the_import_would_write() {
        assertThat(installer.exists("Summarize Text")).isFalse();

        installer.install(entry("Summarize Text"), JSON, ImportMode.SKIP_EXISTING);

        assertThat(installer.exists("Summarize Text")).isTrue();
        assertThat(repository.findById("summarize-text")).isPresent();
    }

    @Test
    void a_name_that_sanitises_to_nothing_still_gets_an_id() {
        assertThat(WorkflowRegistryInstaller.idOf("///")).isEqualTo("workflow");
        assertThat(WorkflowRegistryInstaller.idOf(null)).isEqualTo("workflow");
        assertThat(WorkflowRegistryInstaller.idOf("My Workflow!")).isEqualTo("my-workflow");
    }
}
