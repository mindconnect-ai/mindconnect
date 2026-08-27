package ai.mindconnect.workflow.persistence.port;

import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Stores suspended workflow instances, so a halted run can be picked up later —
 * after an approval, after a delay, after a restart.
 *
 * <p>What is stored is a {@link WorkflowInstanceSnapshot}, not a live
 * {@code WorkflowInstance}. A running instance is bound to a
 * {@code WorkflowContext} — script engines, expression resolvers, event
 * listeners, an output writer — none of which can meaningfully be written to
 * disk or read back. The snapshot holds the progress and nothing else; the
 * runtime is rebuilt around it on resume.
 */
public interface WorkflowInstanceRepository {

    /**
     * Persists a suspended instance. An {@code instanceId} is assigned when the
     * snapshot does not carry one yet.
     *
     * @return the id the snapshot is stored under
     */
    String save(WorkflowInstanceSnapshot snapshot);

    /** The suspended instance with this id, if it is still around. */
    Optional<WorkflowInstanceSnapshot> findById(String instanceId);

    /** Every suspended instance of one workflow, most recently suspended first. */
    List<WorkflowInstanceSnapshot> findByWorkflow(String workflowName);

    /** Every suspended instance, most recently suspended first. */
    List<WorkflowInstanceSnapshot> findAll();

    /** Drops a suspension — once it has been resumed, or abandoned. */
    boolean delete(String instanceId);
}
