package ai.mindconnect.workflow.ui.diagram.app;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory registry of the workflows the editor can mutate. Pre-loaded from
 * {@link WorkflowSamples} on startup so the app is usable immediately; on-disk
 * saves are layered on top so a user's edits survive a restart.
 *
 * <p>The reading and writing of the JSON files is not this class's job — it
 * delegates to the shared {@link FileWorkflowDataRepository}, the one place the
 * "a workflow is a file" rules (id sanitising, atomic writes) live. What stays
 * here is the app's own behaviour: seed from samples, keep a live in-memory copy
 * the editor mutates in place, and only write to disk on an explicit {@link
 * #save}.
 */
@Slf4j
@Component
public class WorkflowStore {

    /**
     * Where saved workflows are written. Relative path; resolved against the
     * JVM's working directory, which for {@code mvn spring-boot:run} is the
     * module root ({@code workflow/mc-workflow-ui-diagram-app/}).
     */
    private static final Path SAVE_DIR = Path.of("workflows");

    private final Map<String, WorkflowData> workflows = new LinkedHashMap<>();
    private final FileWorkflowDataRepository fileRepo = new FileWorkflowDataRepository(SAVE_DIR);

    @PostConstruct
    void load() {
        // Seed from samples first, so new sample workflows show up automatically;
        // any saved file of the same name then replaces its sample.
        for (var entry : WorkflowSamples.SAMPLES.entrySet()) {
            workflows.put(entry.getKey(), entry.getValue().get());
        }
        for (String id : fileRepo.listIds()) {
            fileRepo.findById(id).ifPresent(wf -> {
                if (wf.getName() == null || wf.getName().isBlank()) {
                    wf.setName(id);
                }
                workflows.put(id, wf);
            });
        }
        log.info("WorkflowStore initialised with {} workflows: {}",
                workflows.size(), workflows.keySet());
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    public Set<String> names() {
        return workflows.keySet();
    }

    /**
     * The in-memory {@link WorkflowData} for {@code name}, or {@code null} if
     * there is none. The reference is live — the editor's controller mutates it
     * in place to keep insert/delete/rename round-trips cheap.
     */
    public WorkflowData get(String name) {
        return workflows.get(name);
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Writes the in-memory workflow out through the shared repository.
     *
     * @return the file it was written to
     * @throws IllegalArgumentException if no workflow with that name is loaded
     */
    public Path save(String name) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) {
            throw new IllegalArgumentException("No workflow named '" + name + "'");
        }
        fileRepo.save(name, wf);
        Path target = SAVE_DIR.resolve(name + ".json");
        log.info("Saved workflow {} to {}", name, target.toAbsolutePath());
        return target;
    }
}
