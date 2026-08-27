package ai.mindconnect.workflow.persistence.port;

import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.List;
import java.util.Optional;

/**
 * Stores workflow definitions, one per id — the single place a workflow is kept,
 * shared by every editor and runner.
 *
 * <p>This is the abstraction, not a store: it says nothing about where the
 * workflows live. A {@code FileWorkflowDataRepository} keeps them as JSON files,
 * an {@code InMemoryWorkflowDataRepository} keeps them in a map, and a JPA-backed
 * one can be dropped in later — each an implementation of this one port, so the
 * apps depend on the interface and never on a particular backend.
 *
 * <p>Sits beside {@link WorkflowInstanceRepository}: definitions and suspended
 * instances are the two things a workflow app persists, and both are pluggable
 * from here.
 */
public interface WorkflowDataRepository {

    /** Every stored workflow id, sorted. */
    List<String> listIds();

    /** The workflow stored under {@code id}, or empty if there is none. */
    Optional<WorkflowData> findById(String id);

    /** Writes {@code workflow} under {@code id}, replacing any existing one. */
    void save(String id, WorkflowData workflow);

    /** Removes the workflow under {@code id}. Returns whether one was there. */
    boolean delete(String id);

    /** Whether a workflow is stored under {@code id}. */
    default boolean exists(String id) {
        return findById(id).isPresent();
    }
}
