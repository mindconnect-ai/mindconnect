package ai.mindconnect.workflow.swing.ui;

import ai.mindconnect.workflow.domain.*;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for picking a step type to add.
 * Returns a freshly-created, empty {@link StepData} of the chosen type,
 * or {@code null} if the user cancelled.
 */
public class AddStepDialog extends JDialog {

    private static final String[][] STEP_TYPES = {
        {"Assign Variables",  "assignvariables"},
        {"HTTP Call",         "httpcall"},
        {"Code",              "code"},
        {"If",                "if"},
        {"For Each",          "foreach"},
        {"Block",             "block"},
        {"Call Workflow",     "callworkflow"},
        {"Jump To",           "jumpto"},
        {"Halt",              "halt"},
    };

    private StepData result = null;

    public AddStepDialog(Frame owner) {
        super(owner, "Add Step", true);
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel hint = new JLabel("Choose the step type to add:");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(hint, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
        for (String[] entry : STEP_TYPES) {
            JButton btn = new JButton(entry[0]);
            btn.setPreferredSize(new Dimension(140, 48));
            btn.putClientProperty("type", entry[1]);
            btn.addActionListener(e -> {
                result = createStep((String) btn.getClientProperty("type"));
                dispose();
            });
            grid.add(btn);
        }
        add(grid, BorderLayout.CENTER);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(cancel);
        add(south, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
    }

    /** Returns the chosen step, or {@code null} if cancelled. */
    public StepData getResult() { return result; }

    private static StepData createStep(String type) {
        return switch (type) {
            case "assignvariables" -> new AssignVariablesData();
            case "httpcall"        -> new HttpCallData();
            case "code"            -> new CodeData();
            case "if"              -> new IfData();
            case "foreach"         -> new ForEachData();
            case "block"           -> new BlockData();
            case "callworkflow"    -> new CallWorkflowData();
            case "jumpto"          -> new JumpToData();
            case "halt"            -> new HaltData();
            default                -> throw new IllegalArgumentException("Unknown step type: " + type);
        };
    }
}
