package ai.mindconnect.workflow.admin.service;

import ai.mindconnect.workflow.admin.run.WorkflowRunService;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.edit.StepNames;
import ai.mindconnect.workflow.execution.WorkflowEventListener;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place workflow-admin operations live: definition CRUD, running,
 * and resuming halted instances. Both surfaces delegate here — the admin UI
 * and the external REST API — so the shared rules (step-name normalisation
 * on load, the resume-only-halted guard, the legacy null-status mapping)
 * can never drift between them. Rendering stays with the controllers.
 */
public class WorkflowAdminService {

    /** List row: id is the storage key, name the definition's own name (id when unnamed). */
    public record WorkflowInfo(String id, String name, int steps) {}

    private final WorkflowDataRepository store;
    private final WorkflowInstanceRepository instances;
    private final WorkflowRunService runService;

    public WorkflowAdminService(WorkflowDataRepository store, WorkflowInstanceRepository instances) {
        this.store = store;
        this.instances = instances;
        this.runService = new WorkflowRunService(instances);
    }

    // ── Definitions ────────────────────────────────────────────────────────

    /** All workflows, optionally filtered by a case-insensitive match on id or name. */
    public List<WorkflowInfo> list(String query) {
        String needle = query == null || query.isBlank() ? null : query.toLowerCase();
        return store.listIds().stream()
                .map(id -> {
                    WorkflowData wf = store.findById(id).orElse(null);
                    String name = wf != null && wf.getName() != null && !wf.getName().isBlank()
                            ? wf.getName() : id;
                    int steps = wf != null && wf.getSteps() != null ? wf.getSteps().size() : 0;
                    return new WorkflowInfo(id, name, steps);
                })
                .filter(info -> needle == null
                        || info.id().toLowerCase().contains(needle)
                        || info.name().toLowerCase().contains(needle))
                .toList();
    }

    public Optional<WorkflowData> find(String id) {
        return store.findById(id);
    }

    /**
     * Loads a workflow and makes it addressable: every step gets a unique,
     * readable name. A hand-written file may have unnamed or duplicate steps,
     * and neither can serve as an address — not for the editor's URLs, and
     * not for streamed step events either. Normalising once on load (and
     * persisting it) is what makes plain names reliable everywhere.
     */
    public WorkflowData require(String id) {
        WorkflowData data = store.findById(id).orElseThrow(() -> new NoSuchWorkflowException(id));
        if (StepNames.ensureUnique(data)) {
            store.save(id, data);
        }
        return data;
    }

    public boolean exists(String id) {
        return store.exists(id);
    }

    public void save(String id, WorkflowData wf) {
        store.save(id, wf);
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    // ── Runs ───────────────────────────────────────────────────────────────

    /**
     * Runs the workflow synchronously. The service persists the instance
     * itself: always on a halt, and on a finished run when {@code persist}
     * is set — the instance is the run record.
     */
    public WorkflowRunService.RunReport run(WorkflowData wf, Map<String, Object> params,
                                            WorkflowEventListener extraListener, boolean persist) {
        return runService.run(wf, params, extraListener, persist);
    }

    /** Picks a suspended run back up; overwrites the instance in place. */
    public WorkflowRunService.RunReport resume(WorkflowData wf, WorkflowInstanceSnapshot snapshot,
                                               Map<String, Object> params,
                                               WorkflowEventListener extraListener) {
        return runService.resume(wf, snapshot, params, extraListener);
    }

    public WorkflowRunService.RunReport resume(WorkflowData wf, WorkflowInstanceSnapshot snapshot,
                                               Map<String, Object> params) {
        return runService.resume(wf, snapshot, params);
    }

    // ── Instances ──────────────────────────────────────────────────────────

    public List<WorkflowInstanceSnapshot> instancesOf(String workflowId) {
        return instances.findByWorkflow(workflowId);
    }

    public Optional<WorkflowInstanceSnapshot> instance(String instanceId) {
        return instances.findById(instanceId);
    }

    public boolean deleteInstance(String instanceId) {
        return instances.delete(instanceId);
    }

    public WorkflowInstanceSnapshot requireInstance(String instanceId) {
        return instances.findById(instanceId)
                .orElseThrow(() -> new NoSuchInstanceException(instanceId));
    }

    /** Resume paths only: a finished instance is history, not a suspension. */
    public WorkflowInstanceSnapshot requireHalted(String instanceId) {
        WorkflowInstanceSnapshot snapshot = requireInstance(instanceId);
        if (statusOf(snapshot) != WorkflowInstanceSnapshot.Status.HALTED) {
            throw new NoSuchInstanceException(instanceId);
        }
        return snapshot;
    }

    /** Null status means a file from before the field existed — those were all halted. */
    public static WorkflowInstanceSnapshot.Status statusOf(WorkflowInstanceSnapshot snapshot) {
        return snapshot.getStatus() == null
                ? WorkflowInstanceSnapshot.Status.HALTED : snapshot.getStatus();
    }
}
