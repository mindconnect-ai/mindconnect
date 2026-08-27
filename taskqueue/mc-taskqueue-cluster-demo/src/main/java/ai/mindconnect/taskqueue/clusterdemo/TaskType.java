package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.ui.model.UiField;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One selectable entry in the "New Task" form: its parameter fields (built
 * fresh per render — {@link UiField}s are mutable) and the translation of the
 * flat form values into a {@link TaskSubmission}. Validation failures are
 * {@link IllegalArgumentException}s; the controller renders them as form errors.
 */
public record TaskType(String id,
                           String label,
                           String description,
                           Supplier<List<UiField>> fields,
                           Function<Map<String, Object>, TaskSubmission> toSubmission) {
}
