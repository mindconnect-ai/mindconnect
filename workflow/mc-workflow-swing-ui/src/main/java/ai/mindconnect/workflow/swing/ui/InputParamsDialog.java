package ai.mindconnect.workflow.swing.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Modal dialog for reviewing and entering workflow execution parameters.
 *
 * <p>Pre-population priority (highest wins):
 * <ol>
 *   <li>Last-used values from the previous run ({@code lastUsed} map)</li>
 *   <li>Default values declared on the workflow ({@code paramDefaults} map)</li>
 *   <li>Empty string</li>
 * </ol>
 *
 * <p>Declared parameters (from {@code declaredParams}) are shown first and
 * always present, even when blank.  The user can also add ad-hoc rows for
 * extra parameters not declared on the workflow.
 *
 * <p>After the dialog closes via Run, call {@link #getParams()} for the
 * values to pass to the executor, and {@link #getLastUsed()} to persist the
 * entries for the next invocation.
 */
public class InputParamsDialog extends JDialog {

    private final DefaultTableModel tableModel;
    private final JTable table;
    private Map<String, Object> params  = null;   // null = cancelled
    private Map<String, String> lastUsed = null;  // null = cancelled

    /**
     * @param owner         parent frame
     * @param declaredParams parameter names declared on the workflow (in order)
     * @param paramDefaults  default values declared on the workflow
     * @param lastUsed       values from the most recent run (may be null/empty)
     */
    public InputParamsDialog(Frame owner,
                             List<String> declaredParams,
                             Map<String, String> paramDefaults,
                             Map<String, String> lastUsed) {
        super(owner, "Execution Parameters", true);
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel hint = new JLabel(
                "<html>Review and edit parameter values, then click <b>Run</b>.</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(hint, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new Object[]{"Parameter Name", "Value"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return true; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(24);

        // Pre-populate declared params (last-used wins over default wins over blank)
        Set<String> declared = new LinkedHashSet<>(
                declaredParams != null ? declaredParams : Collections.emptyList());
        Map<String, String> prev = lastUsed != null ? lastUsed : Collections.emptyMap();
        Map<String, String> defs = paramDefaults != null ? paramDefaults : Collections.emptyMap();

        for (String name : declared) {
            String value = prev.containsKey(name) ? prev.get(name)
                         : defs.containsKey(name) ? defs.get(name)
                         : "";
            tableModel.addRow(new Object[]{name, value});
        }

        // Add any extra keys from last-used that are NOT declared (ad-hoc params from prev run)
        for (Map.Entry<String, String> e : prev.entrySet()) {
            if (!declared.contains(e.getKey())) {
                tableModel.addRow(new Object[]{e.getKey(), e.getValue()});
            }
        }

        // Always have at least one blank row so the user can add params easily
        if (tableModel.getRowCount() == 0) {
            tableModel.addRow(new Object[]{"", ""});
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(460, 200));
        add(scroll, BorderLayout.CENTER);

        // Row management buttons + Run/Cancel
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addRow = new JButton("+ Row");
        JButton delRow = new JButton("- Row");
        addRow.addActionListener(e -> {
            tableModel.addRow(new Object[]{"", ""});
            int newRow = tableModel.getRowCount() - 1;
            table.setRowSelectionInterval(newRow, newRow);
            table.scrollRectToVisible(table.getCellRect(newRow, 0, true));
        });
        delRow.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) tableModel.removeRow(r);
        });
        buttons.add(addRow);
        buttons.add(delRow);

        JButton run = new JButton("Run");
        run.setFont(run.getFont().deriveFont(Font.BOLD));
        run.addActionListener(e -> {
            // Commit any in-progress cell edit
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            params   = collectParams();
            this.lastUsed = collectLastUsed();
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.WEST);
        JPanel runCancel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        runCancel.add(cancel);
        runCancel.add(run);
        south.add(runCancel, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(run);
        pack();
        setLocationRelativeTo(owner);
    }

    // -----------------------------------------------------------------------
    // Results
    // -----------------------------------------------------------------------

    /** Returns the collected params to pass to the executor, or {@code null} if cancelled. */
    public Map<String, Object> getParams() { return params; }

    /**
     * Returns the String values entered in this run, suitable for passing back
     * as {@code lastUsed} next time.  Returns {@code null} if the dialog was cancelled.
     */
    public Map<String, String> getLastUsed() { return lastUsed; }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> collectParams() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String k = str(tableModel.getValueAt(i, 0));
            String v = str(tableModel.getValueAt(i, 1));
            if (!k.isBlank()) map.put(k, v);
        }
        return map;
    }

    private Map<String, String> collectLastUsed() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String k = str(tableModel.getValueAt(i, 0));
            String v = str(tableModel.getValueAt(i, 1));
            if (!k.isBlank()) map.put(k, v);
        }
        return map;
    }

    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }
}
