package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.jackson.JsonMapperFactory;
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

    private final ObjectMapper mapper;

    /** @param json the host's mapper factory, which the workflow store's mapper is built from too */
    public StepJsonMapper(JsonMapperFactory json) {
        this.mapper = WorkflowObjectMapperFactory.create(json);
    }

    /**
     * The step's properties as an ordered map — what the details dialog walks
     * to show every populated field without a per-type view. Children and the
     * {@code @class} discriminator are in the map too; the caller skips what
     * it renders elsewhere.
     */
    public java.util.Map<String, Object> toMap(StepData step) {
        return mapper.convertValue(step,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
    }

    /** Pretty-printed JSON for a single property value (a map or list). */
    public String prettyValue(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    /** Pretty-printed JSON for {@code step}, including its children. */
    public String toJson(StepData step) {
        try {
            return mapper.writeValueAsString(step);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot render step as JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * Parses an edited step. Throws {@link InvalidStepJsonException} with a
     * message fit to show the user — malformed JSON is expected input here,
     * not a server fault.
     */
    public StepData fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidStepJsonException("The JSON is empty.");
        }
        try {
            return mapper.readValue(json, StepData.class);
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
