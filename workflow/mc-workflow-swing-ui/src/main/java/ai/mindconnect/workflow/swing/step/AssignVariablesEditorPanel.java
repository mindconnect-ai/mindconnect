package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for {@link AssignVariablesData}.
 * Shows a two-column table: variable name | value/expression.
 */
public class AssignVariablesEditorPanel extends StepEditorPanel<AssignVariablesData> {

    private final JTextField nameField = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final DefaultTableModel tableModel;
    private final JTable table;

    public AssignVariablesEditorPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Top: name + assignResultToVar
        JPanel top = new JPanel(new GridLayout(2, 2, 4, 4));
        top.add(new JLabel("Step name:"));
        top.add(nameField);
        top.add(new JLabel("Assign result to var:"));
        top.add(assignResultField);
        add(top, BorderLayout.NORTH);

        // Centre: assignments table
        tableModel = new DefaultTableModel(new Object[]{"Variable", "Value / Expression"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return true; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(22);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Bottom: add / remove row buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton add = new JButton("+ Row");
        JButton remove = new JButton("- Row");
        add.addActionListener(e -> tableModel.addRow(new Object[]{"", ""}));
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) tableModel.removeRow(row);
        });
        buttons.add(add);
        buttons.add(remove);
        add(buttons, BorderLayout.SOUTH);
    }

    @Override
    public void populate(AssignVariablesData step) {
        nameField.setText(step.getName() != null ? step.getName() : "");
        assignResultField.setText(step.getAssignResultToVar() != null ? step.getAssignResultToVar() : "");
        tableModel.setRowCount(0);
        if (step.getVariableAssignments() != null) {
            for (VariableAssignment va : step.getVariableAssignments()) {
                tableModel.addRow(new Object[]{va.getVarName(), va.getExpressionOrVarName()});
            }
        }
    }

    @Override
    public void applyTo(AssignVariablesData step) {
        step.setName(nameField.getText().trim());
        step.setAssignResultToVar(assignResultField.getText().trim());
        List<VariableAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String varName = str(tableModel.getValueAt(i, 0));
            String expr    = str(tableModel.getValueAt(i, 1));
            if (!varName.isBlank()) assignments.add(new VariableAssignment(varName, expr));
        }
        step.setVariableAssignments(assignments);
    }

    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }
}
