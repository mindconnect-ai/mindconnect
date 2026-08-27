package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.StepData;

import javax.swing.*;

/**
 * Base class for per-step-type editor panels shown in the detail area.
 *
 * <p>Subclasses fill their own Swing components in the constructor and
 * implement {@link #applyTo(StepData)} to write field values back
 * to the domain object.
 */
public abstract class StepEditorPanel<T extends StepData> extends JPanel {

    /** Populate UI fields from {@code step}. */
    public abstract void populate(T step);

    /** Write UI field values back into {@code step}. */
    public abstract void applyTo(T step);
}
