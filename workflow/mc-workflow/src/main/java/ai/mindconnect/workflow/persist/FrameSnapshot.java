package ai.mindconnect.workflow.persist;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One suspended container on the path from the workflow down to the halt.
 *
 * <p>A snapshot is not the whole execution — it is the <em>resume point</em>.
 * When a workflow halts, every container between the root and the halt step is
 * left half-finished: each one knows which step it would run next, and holds the
 * variables the steps below it can see. That chain, and nothing else, is what a
 * resume needs. The steps that already ran are history; replaying them is
 * exactly what a resume must <em>not</em> do.
 *
 * <p>Plain data, deliberately: the live instances hang off a {@code
 * WorkflowContext} full of script engines and listeners, none of which belongs
 * on disk. They are rebuilt on restore, never deserialised.
 */
@Data
public class FrameSnapshot {

    /** How to find (or rebuild) the definition this frame was executing. */
    public enum Kind {
        /** The workflow itself. */
        ROOT,
        /** A step in the parent container's step list, at {@link #index}. */
        STEP,
        /** One iteration of a for-each; {@link #index} is the iteration number. */
        FOREACH_ITERATION,
        /** The then-block of the parent if's condition {@link #index}. */
        IF_THEN,
        /** The parent if's else-block. */
        IF_ELSE
    }

    private Kind kind;

    /** Meaning depends on {@link #kind}; -1 when it carries none. */
    private int index = -1;

    /** The step's name — carried for diagnostics, and to catch a mismatched restore. */
    private String stepName;

    /** Where the container would carry on. The heart of the whole snapshot. */
    private int nextStepIndex;
    private int currentStepIndex;

    /** The container's result so far. */
    private Object result;

    /**
     * The variables declared <em>directly</em> in this frame's scope. Parent
     * scopes are the frames above; rebuilding the chain rebuilds the lookup.
     */
    private Map<String, Object> variables = new LinkedHashMap<>();

    /** For-each only: iterations already finished, and what they produced. */
    private int currentRunIndex;
    private List<Object> resultList = new ArrayList<>();

    /**
     * The container below this one that was itself suspended, if any. Exactly
     * one child can be suspended — a halt stops one line of execution — so this
     * is a chain, not a tree.
     */
    private FrameSnapshot haltedChild;
}
