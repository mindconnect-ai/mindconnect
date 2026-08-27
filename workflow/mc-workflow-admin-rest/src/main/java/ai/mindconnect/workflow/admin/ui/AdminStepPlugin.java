package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.workflow.domain.StepData;

import java.util.List;
import java.util.Map;

/**
 * SPI for contributing step types to the workflow admin's editor without the
 * admin depending on the contributing module. A module that ships a custom
 * step (its {@code StepData} + engine registration via {@code
 * WorkflowConfigurer}) implements this to make the step authorable: an entry
 * in the add-step picker, a typed edit form, and the mapping of submitted
 * values back onto the step.
 *
 * <p>Discovery: {@link java.util.ServiceLoader}, via
 * {@code META-INF/services/ai.mindconnect.workflow.admin.ui.AdminStepPlugin}.
 * The base fields (name, assign-result-to-var) and the raw-JSON escape hatch
 * are provided by the admin for every step; a plugin only adds its own fields.
 */
public interface AdminStepPlugin {

    /** Entries for the add-step picker; value = the step's type discriminator. */
    List<UiField.Option> typeOptions();

    /** A fresh step for {@code type}, or {@code null} when the type isn't this plugin's. */
    StepData create(String type);

    /**
     * Adds this plugin's fields to the edit form. Return {@code true} when
     * {@code step} was handled (the admin then skips its built-in mapping).
     */
    default boolean buildForm(StepData step, UiForm form) {
        return false;
    }

    /**
     * Applies submitted form values onto {@code step}. Return {@code true}
     * when handled.
     */
    default boolean apply(StepData step, Map<String, Object> form) {
        return false;
    }
}
