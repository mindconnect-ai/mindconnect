package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.BlockData;

import javax.swing.*;
import java.awt.*;

/**
 * Editor for {@link BlockData} — a named group of child steps.
 *
 * <p>Shows name, result-from variable, and an embedded {@link StepsTablePanel}
 * that lets the user add, remove and reorder the block's child steps.
 */
public class BlockEditorPanel extends StepEditorPanel<BlockData> {

    private final JTextField nameField       = new JTextField();
    private final JTextField resultFromField = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final StepsTablePanel stepsPanel;

    public BlockEditorPanel(EditorPanelFactory factory) {
        stepsPanel = new StepsTablePanel("Child Steps", factory);
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new GridLayout(3, 2, 4, 4));
        top.add(new JLabel("Step name:"));        top.add(nameField);
        top.add(new JLabel("Result from var:"));  top.add(resultFromField);
        top.add(new JLabel("Assign result to:")); top.add(assignResultField);
        add(top, BorderLayout.NORTH);
        add(stepsPanel, BorderLayout.CENTER);
    }

    @Override
    public void populate(BlockData step) {
        nameField.setText(nvl(step.getName()));
        resultFromField.setText(nvl(step.getResultFrom()));
        assignResultField.setText(nvl(step.getAssignResultToVar()));
        stepsPanel.load(step.getSteps());
    }

    @Override
    public void applyTo(BlockData step) {
        step.setName(nameField.getText().trim());
        step.setResultFrom(blankToNull(resultFromField.getText()));
        step.setAssignResultToVar(blankToNull(assignResultField.getText()));
        step.setSteps(stepsPanel.collectSteps());
    }

    private static String nvl(String s) { return s != null ? s : ""; }
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
