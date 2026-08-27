package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.BaseStepData;
import ai.mindconnect.workflow.domain.StepData;

import javax.swing.*;
import java.awt.*;

/**
 * Fallback editor for step types that don't have a dedicated panel.
 * Shows name and assignResultToVar; the JSON preview is read-only.
 */
public class GenericStepEditorPanel extends StepEditorPanel<StepData> {

    private final JTextField nameField         = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final JLabel typeLabel             = new JLabel();

    public GenericStepEditorPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new GridLayout(3, 2, 4, 4));
        top.add(new JLabel("Type:"));             top.add(typeLabel);
        top.add(new JLabel("Step name:"));        top.add(nameField);
        top.add(new JLabel("Assign result to:")); top.add(assignResultField);
        add(top, BorderLayout.NORTH);

        JLabel hint = new JLabel(
                "<html><i>No dedicated editor for this step type.<br>" +
                "Edit name and result variable here; other fields are preserved.</i></html>");
        hint.setForeground(Color.GRAY);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        add(hint, BorderLayout.CENTER);
    }

    @Override
    public void populate(StepData step) {
        typeLabel.setText(step.getType());
        nameField.setText(step.getName() != null ? step.getName() : "");
        assignResultField.setText(step.getAssignResultToVar() != null ? step.getAssignResultToVar() : "");
    }

    @Override
    public void applyTo(StepData step) {
        if (step instanceof BaseStepData b) {
            b.setName(nameField.getText().trim());
            b.setAssignResultToVar(assignResultField.getText().trim());
        }
    }
}
