package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.ForEachData;

import javax.swing.*;
import java.awt.*;

/**
 * Editor for {@link ForEachData}.
 *
 * <p>Top section: iteration metadata (loopOver, runVar, indexVar, flags).
 * Centre: embedded {@link StepsTablePanel} for the child steps that run
 * once per element.
 */
public class ForEachEditorPanel extends StepEditorPanel<ForEachData> {

    private final JTextField nameField        = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final JTextField resultFromField   = new JTextField();
    private final JTextField loopOverField     = new JTextField();
    private final JTextField runVarField       = new JTextField();
    private final JTextField indexVarField     = new JTextField();
    private final JCheckBox  parallelBox       = new JCheckBox("Run iterations in parallel");
    private final JCheckBox  joinResultsBox    = new JCheckBox("Join results");
    private final JTextField joinDelimField    = new JTextField();
    private final StepsTablePanel stepsPanel;

    public ForEachEditorPanel(EditorPanelFactory factory) {
        stepsPanel = new StepsTablePanel("Body Steps (executed per element)", factory);
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints lc = lc();
        GridBagConstraints fc = fc();

        int row = 0;
        addRow(top, lc, fc, row++, "Step name:",          nameField);
        addRow(top, lc, fc, row++, "Assign result to:",   assignResultField);
        addRow(top, lc, fc, row++, "Result from var:",    resultFromField);
        addRow(top, lc, fc, row++, "Loop over (var/expr):", loopOverField);
        addRow(top, lc, fc, row++, "Run variable:",       runVarField);
        addRow(top, lc, fc, row++, "Index variable:",     indexVarField);
        addRow(top, lc, fc, row++, "Join delimiter:",     joinDelimField);

        // Checkboxes span both columns
        GridBagConstraints span = new GridBagConstraints();
        span.gridx = 0; span.gridwidth = 2; span.anchor = GridBagConstraints.WEST;
        span.insets = new Insets(2, 2, 0, 2);
        span.gridy = row++;
        top.add(parallelBox, span);
        span.gridy = row;
        top.add(joinResultsBox, span);

        // Disable delimiter unless joinResults is on
        joinDelimField.setEnabled(false);
        joinResultsBox.addActionListener(e -> joinDelimField.setEnabled(joinResultsBox.isSelected()));

        add(top, BorderLayout.NORTH);
        add(stepsPanel, BorderLayout.CENTER);
    }

    @Override
    public void populate(ForEachData step) {
        nameField.setText(nvl(step.getName()));
        assignResultField.setText(nvl(step.getAssignResultToVar()));
        resultFromField.setText(nvl(step.getResultFrom()));
        loopOverField.setText(nvl(step.getLoopOver()));
        runVarField.setText(nvl(step.getRunVar()));
        indexVarField.setText(nvl(step.getIndexVar()));
        parallelBox.setSelected(step.isParallel());
        joinResultsBox.setSelected(step.isJoinResults());
        joinDelimField.setText(nvl(step.getJoinDelimiter()));
        joinDelimField.setEnabled(step.isJoinResults());
        stepsPanel.load(step.getSteps());
    }

    @Override
    public void applyTo(ForEachData step) {
        step.setName(nameField.getText().trim());
        step.setAssignResultToVar(blankToNull(assignResultField.getText()));
        step.setResultFrom(blankToNull(resultFromField.getText()));
        step.setLoopOver(loopOverField.getText().trim());
        step.setRunVar(blankToNull(runVarField.getText()));
        step.setIndexVar(blankToNull(indexVarField.getText()));
        step.setParallel(parallelBox.isSelected());
        step.setJoinResults(joinResultsBox.isSelected());
        step.setJoinDelimiter(blankToNull(joinDelimField.getText()));
        step.setSteps(stepsPanel.collectSteps());
    }

    private static void addRow(JPanel p, GridBagConstraints lc, GridBagConstraints fc,
                                int row, String label, JComponent field) {
        lc.gridy = row; p.add(new JLabel(label), lc);
        fc.gridy = row; p.add(field, fc);
    }

    private static GridBagConstraints lc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 2, 2, 4);
        return c;
    }

    private static GridBagConstraints fc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1; c.insets = new Insets(2, 0, 2, 2);
        return c;
    }

    private static String nvl(String s) { return s != null ? s : ""; }
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
