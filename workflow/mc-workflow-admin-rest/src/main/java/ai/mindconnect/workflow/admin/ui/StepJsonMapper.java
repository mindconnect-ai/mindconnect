package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders one step as JSON and reads an edited step back. Uses the same mapper
 * as the workflow store, so what the raw editor shows is byte-for-byte the
 * subtree as it sits in the workflow file — including the {@code @class}
 * discriminator that selects the step type on the way back in.
 *
 * <p>The raw editor is the escape hatch for everything the typed forms don't
 * cover yet (if-conditions, HTTP headers, call-workflow params): a container's
 * JSON carries its children, so editing it edits the whole subtree at once.
 */
public final class StepJsonMapper {

    private static final ObjectMapper MAPPER = WorkflowObjectMapperFactory.create();

    private StepJsonMapper() {}

    /** Pretty-printed JSON for {@code step}, including its children. */
    public static String toJson(StepData step) {
        try {
            return MAPPER.writeValueAsString(step);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot render step as JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * Parses an edited step. Throws {@link InvalidStepJsonException} with a
     * message fit to show the user — malformed JSON is expected input here,
     * not a server fault.
     */
    public static StepData fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidStepJsonException("The JSON is empty.");
        }
        try {
            return MAPPER.readValue(json, StepData.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidStepJsonException(describe(ex));
        }
    }

    /** A one-line, user-facing rendering of a Jackson failure. */
    private static String describe(JsonProcessingException ex) {
        String msg = ex.getOriginalMessage();
        if (ex.getLocation() != null && ex.getLocation().getLineNr() > 0) {
            return msg + " (line " + ex.getLocation().getLineNr()
                    + ", column " + ex.getLocation().getColumnNr() + ")";
        }
        return msg;
    }

    /** Raised when the submitted JSON is not a valid step. */
    public static class InvalidStepJsonException extends RuntimeException {
        public InvalidStepJsonException(String message) {
            super(message);
        }
    }
}
