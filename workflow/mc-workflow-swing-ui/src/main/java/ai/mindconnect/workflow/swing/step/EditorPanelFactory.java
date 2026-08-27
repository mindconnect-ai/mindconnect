package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.*;

import java.util.Collection;

/**
 * Creates the correct {@link StepEditorPanel} for any {@link StepData} subtype.
 *
 * <p>Centralising this logic here lets both {@link ai.mindconnect.workflow.swing.ui.WorkflowDesignerFrame}
 * and {@link StepsTablePanel} (embedded child-step lists) reuse the same
 * mapping without coupling to the top-level frame.
 *
 * <p>The factory holds the registered scripting languages needed by
 * {@link CodeEditorPanel}; inject it once at application startup and share
 * the instance everywhere.
 */
public class EditorPanelFactory {

    private final Collection<String> registeredLanguages;

    public EditorPanelFactory(Collection<String> registeredLanguages) {
        this.registeredLanguages = registeredLanguages;
    }

    /** Returns a fresh, uninitialised editor panel for the given step. */
    @SuppressWarnings("rawtypes")
    public StepEditorPanel createFor(StepData step) {
        return switch (step) {
            case AssignVariablesData s -> new AssignVariablesEditorPanel();
            case HttpCallData s -> new HttpCallEditorPanel();
            case CodeData            s -> new CodeEditorPanel(registeredLanguages);
            case ForEachData s -> new ForEachEditorPanel(this);
            case BlockData s -> new BlockEditorPanel(this);
            case IfData              s -> new IfEditorPanel(this);
            default                    -> new GenericStepEditorPanel();
        };
    }
}
