package ai.mindconnect.agent.tools.workflow.registry;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Installs a {@code workflow} entry into the host's workflow store — the same
 * store the workflow admin writes to and the workflow tools read, so an
 * imported workflow is editable, runnable and callable by agents on the next
 * lookup, without a restart.
 *
 * <p>This installer lives here rather than with the other two because this is
 * the module that has a workflow store at all. An installation without a
 * workflow engine has no installer for the kind, and its registry screen says
 * so on the entry instead of failing at import time.
 *
 * <p>A workflow is addressed by its id, which is its file name — there is no
 * separate name field to fall back on — so the entry's name becomes the id,
 * sanitised the way the store sanitises it anyway.
 */
public class WorkflowRegistryInstaller implements RegistryInstaller {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistryInstaller.class);

    private final WorkflowDataRepository repository;
    private final JacksonWorkflowSerializer serializer;

    public WorkflowRegistryInstaller(WorkflowDataRepository repository) {
        this(repository, new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create()));
    }

    public WorkflowRegistryInstaller(WorkflowDataRepository repository,
                                     JacksonWorkflowSerializer serializer) {
        this.repository = repository;
        this.serializer = serializer;
    }

    @Override
    public RegistryItemType type() {
        return RegistryItemType.WORKFLOW;
    }

    @Override
    public boolean exists(String name) {
        return name != null && repository.exists(idOf(name));
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) {
        WorkflowData workflow = serializer.read(content);
        String id = idOf(entry.name());
        boolean present = repository.exists(id);

        if (present && mode == ImportMode.SKIP_EXISTING) {
            return ImportedItem.skipped(entry, id, "a workflow with this id is already here");
        }
        repository.save(id, workflow);
        log.info("Imported workflow '{}' from registry entry '{}'", id, entry.id());
        return new ImportedItem(entry.id(), type(), id,
                present ? ImportStatus.UPDATED : ImportStatus.IMPORTED, null);
    }

    /**
     * The entry's name as a workflow id: lower case, and everything that is
     * not a letter, a digit, {@code -} or {@code _} becomes a hyphen. The
     * store sanitises ids on the way to disk regardless; doing it here too
     * means {@link #exists} asks about the same id the import will write.
     */
    static String idOf(String name) {
        String id = name == null ? "" : name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return id.isEmpty() ? "workflow" : id;
    }
}
