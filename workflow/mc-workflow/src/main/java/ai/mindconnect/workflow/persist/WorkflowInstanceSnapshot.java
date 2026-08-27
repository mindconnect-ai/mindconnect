package ai.mindconnect.workflow.persist;

import lombok.Data;

/**
 * One workflow run, in a form that survives a restart. The instance IS the run
 * record: it is written when a run halts (so it can be resumed) or is kept, and
 * it stays around after the run finished — same id, final status — so history
 * is one entity, not a snapshot plus a separate record pointing at it.
 *
 * @see FrameSnapshot for why the frames are the resume chain rather than the
 *      whole execution; the readable step-by-step history lives in
 *      {@code runTraceJson} instead
 */
@Data
public class WorkflowInstanceSnapshot {

    /**
     * Where this run stands. {@code HALTED} is the only status with a live
     * resume chain in {@link #root}; the other two are pure history. A null
     * status (files written before the field existed) means {@code HALTED} —
     * only halted instances were stored back then.
     */
    public enum Status { HALTED, FINISHED, ERROR }

    /** Identifies this run; assigned by whoever stores it. */
    private String instanceId;

    private Status status;

    /** Epoch millis of the run's start — halts and resumes don't change it. */
    private long startedAt;

    /**
     * The run's step-by-step trace so far, as JSON. Opaque to the engine: the
     * admin layer serialises its run report into it on every save and appends
     * each resumed segment, so the full history travels with the instance —
     * across halts, resumes and restarts. Null for legacy files.
     */
    private String runTraceJson;

    /** The workflow this instance is running — the id the definition is stored under. */
    private String workflowName;

    /**
     * The shape of the definition when the run started.
     *
     * <p>The resume pointer is an <em>index</em> into a step list. Insert a step
     * above the halt in the editor and that index silently points at a different
     * step. A workflow being edited while an instance sleeps is not a corner
     * case — it is a Tuesday — so the fingerprint is checked on restore and a
     * changed definition is refused rather than resumed into the wrong place.
     */
    private String definitionFingerprint;

    /** Epoch millis of the last save; set by the caller — the engine has no clock of its own here. */
    private long suspendedAt;

    /** The resume chain. Only present while {@link #status} is {@code HALTED}. */
    private FrameSnapshot root;
}
