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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    /** Reads a serialised workflow as a plain tree, to search it for references. */
    private static final ObjectMapper TREE_READER = new ObjectMapper();

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

    /**
     * Workflows whose steps call that agent, run an inline agent on that LLM
     * config, or call that workflow. Read from each workflow's serialised form,
     * so a reference nested in a loop or a branch is found the same way.
     */
    @Override
    public List<String> referencesTo(RegistryItemType type, String name) {
        if (name == null || type == RegistryItemType.PACKAGE) {
            return List.of();
        }
        List<String> referring = new ArrayList<>();
        for (String id : repository.listIds()) {
            if (type == RegistryItemType.WORKFLOW && id.equals(idOf(name))) {
                continue;
            }
            try {
                Optional<WorkflowData> workflow = repository.findById(id);
                if (workflow.isPresent()
                        && refersTo(TREE_READER.readTree(serializer.write(workflow.get())), type, name)) {
                    referring.add(id);
                }
            } catch (Exception unreadable) {
                log.debug("Workflow '{}' not searched for references: {}", id, unreadable.getMessage());
            }
        }
        return referring;
    }

    private static boolean refersTo(JsonNode node, RegistryItemType type, String name) {
        if (node.isObject()) {
            String stepClass = node.path("@class").asText("");
            boolean hit = switch (type) {
                case AGENT -> stepClass.endsWith(".AgentCallData") && name.equals(node.path("agent").asText(null));
                case LLM_CONFIG -> name.equals(node.path("llmConfigName").asText(null));
                case WORKFLOW -> stepClass.endsWith(".CallWorkflowData")
                        && name.equals(node.path("workflow").asText(null));
                case PACKAGE -> false;
            };
            if (hit) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (refersTo(child, type, name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ImportedItem remove(RegistryEntry entry) {
        String id = idOf(entry.name());
        if (!repository.delete(id)) {
            return ImportedItem.skipped(entry, id, "no workflow with this id is here");
        }
        log.info("Removed workflow '{}' (registry entry '{}')", id, entry.id());
        return ImportedItem.removed(entry, id);
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
