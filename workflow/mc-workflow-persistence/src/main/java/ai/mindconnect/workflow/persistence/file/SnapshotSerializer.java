package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.persist.FrameSnapshot;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/**
 * Reads and writes a {@link WorkflowInstanceSnapshot} as JSON.
 *
 * <p><b>The contract for variables:</b> only JSON-representable values survive a
 * suspension. A workflow variable is an {@code Object} — a script may leave
 * anything in one — and there is no honest way to write a script engine's own
 * object, an open stream or a database handle to a file and read it back as the
 * same thing. Numbers, strings, booleans, lists and maps come back as
 * themselves; a POJO comes back as a map, which is what JSON can promise.
 *
 * <p>Anything that cannot be written at all is reported by name, at the moment
 * of saving, rather than at three in the morning when someone tries to resume.
 */
public class SnapshotSerializer {

    private final ObjectMapper mapper;

    public SnapshotSerializer() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String toJson(WorkflowInstanceSnapshot snapshot) {
        checkWritable(snapshot.getRoot(), snapshot.getWorkflowName());
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new UnwritableSnapshotException(
                    "Cannot write the suspended instance of '" + snapshot.getWorkflowName()
                    + "': " + ex.getMessage(), ex);
        }
    }

    public WorkflowInstanceSnapshot fromJson(String json) {
        try {
            return mapper.readValue(json, WorkflowInstanceSnapshot.class);
        } catch (Exception ex) {
            throw new UnwritableSnapshotException(
                    "Cannot read a suspended instance: " + ex.getMessage(), ex);
        }
    }

    /**
     * Names the variable that cannot be written, instead of letting Jackson fail
     * somewhere deep with a message about the value's class. A workflow that
     * parks a script object in a variable and then halts is a mistake worth
     * pointing at precisely.
     */
    private void checkWritable(FrameSnapshot frame, String workflowName) {
        if (frame == null) return;
        for (Map.Entry<String, Object> variable : frame.getVariables().entrySet()) {
            try {
                mapper.writeValueAsString(variable.getValue());
            } catch (Exception ex) {
                Object value = variable.getValue();
                throw new UnwritableSnapshotException(
                        "Workflow '" + workflowName + "' cannot be suspended: the variable '"
                        + variable.getKey() + "' in scope '" + frame.getStepName() + "' holds a "
                        + (value == null ? "null" : value.getClass().getName())
                        + ", which cannot be written to JSON. Only values that survive a JSON "
                        + "round trip can outlive a suspension.", ex);
            }
        }
        checkWritable(frame.getHaltedChild(), workflowName);
    }

    /** A snapshot could not be written, or read back. */
    public static class UnwritableSnapshotException extends RuntimeException {
        public UnwritableSnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
