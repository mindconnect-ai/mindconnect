package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.VariableAssignment;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for {@link HttpCallData}.
 */
public class HttpCallEditorPanel extends StepEditorPanel<HttpCallData> {

    private static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"};

    private final JTextField nameField         = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final JTextField urlField          = new JTextField();
    private final JComboBox<String> methodBox  = new JComboBox<>(METHODS);
    private final JTextField contentTypeField  = new JTextField();
    private final JTextField timeoutField      = new JTextField("0");
    private final JCheckBox failOnErrorBox     = new JCheckBox("Fail on error", true);
    private final JTextField statusCodeVarField     = new JTextField();
    private final JTextField responseHeadersVarField = new JTextField();
    private final JTextArea bodyArea           = new JTextArea(5, 40);
    private final DefaultTableModel headersModel;
    private final JTable headersTable;

    public HttpCallEditorPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Top: basic fields
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.anchor = GridBagConstraints.WEST; l.insets = new Insets(2, 2, 2, 4);
        GridBagConstraints f = new GridBagConstraints();
        f.fill = GridBagConstraints.HORIZONTAL; f.weightx = 1; f.insets = new Insets(2, 0, 2, 2);

        int row = 0;
        addRow(top, l, f, row++, "Step name:",          nameField);
        addRow(top, l, f, row++, "Assign result to:",   assignResultField);
        addRow(top, l, f, row++, "URL:",                urlField);
        addRow(top, l, f, row++, "Method:",             methodBox);
        addRow(top, l, f, row++, "Content-Type:",       contentTypeField);
        addRow(top, l, f, row++, "Timeout (ms):",       timeoutField);
        addRow(top, l, f, row++, "Status code var:",    statusCodeVarField);
        addRow(top, l, f, row++, "Response headers var:", responseHeadersVarField);

        GridBagConstraints cb = new GridBagConstraints();
        cb.gridx = 0; cb.gridy = row; cb.gridwidth = 2; cb.anchor = GridBagConstraints.WEST;
        cb.insets = new Insets(2, 2, 2, 2);
        top.add(failOnErrorBox, cb);

        add(top, BorderLayout.NORTH);

        // Centre: headers table + body
        headersModel = new DefaultTableModel(new Object[]{"Header Name", "Value / Expression"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return true; }
        };
        headersTable = new JTable(headersModel);
        headersTable.setRowHeight(22);
        JScrollPane headersScroll = new JScrollPane(headersTable);
        headersScroll.setPreferredSize(new Dimension(0, 100));

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addHeader = new JButton("+ Header");
        JButton removeHeader = new JButton("- Header");
        addHeader.addActionListener(e -> headersModel.addRow(new Object[]{"", ""}));
        removeHeader.addActionListener(e -> {
            int r = headersTable.getSelectedRow();
            if (r >= 0) headersModel.removeRow(r);
        });
        headerButtons.add(addHeader);
        headerButtons.add(removeHeader);

        JPanel headersPanel = new JPanel(new BorderLayout(2, 2));
        headersPanel.setBorder(BorderFactory.createTitledBorder("Request Headers"));
        headersPanel.add(headersScroll, BorderLayout.CENTER);
        headersPanel.add(headerButtons, BorderLayout.SOUTH);

        bodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane bodyScroll = new JScrollPane(bodyArea);
        bodyScroll.setBorder(BorderFactory.createTitledBorder("Request Body"));

        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT, headersPanel, bodyScroll);
        centre.setResizeWeight(0.4);
        add(centre, BorderLayout.CENTER);
    }

    private void addRow(JPanel p, GridBagConstraints l, GridBagConstraints f,
                        int row, String label, JComponent field) {
        l.gridx = 0; l.gridy = row; p.add(new JLabel(label), l);
        f.gridx = 1; f.gridy = row; p.add(field, f);
    }

    @Override
    public void populate(HttpCallData step) {
        nameField.setText(nvl(step.getName()));
        assignResultField.setText(nvl(step.getAssignResultToVar()));
        urlField.setText(nvl(step.getUrl()));
        methodBox.setSelectedItem(step.getMethod() != null ? step.getMethod().toUpperCase() : "GET");
        contentTypeField.setText(nvl(step.getContentType()));
        timeoutField.setText(String.valueOf(step.getTimeoutMs()));
        failOnErrorBox.setSelected(step.isFailOnError());
        statusCodeVarField.setText(nvl(step.getStatusCodeVar()));
        responseHeadersVarField.setText(nvl(step.getResponseHeadersVar()));
        bodyArea.setText(nvl(step.getBody()));
        bodyArea.setCaretPosition(0);
        headersModel.setRowCount(0);
        if (step.getHeaders() != null) {
            for (VariableAssignment h : step.getHeaders()) {
                headersModel.addRow(new Object[]{h.getVarName(), h.getExpressionOrVarName()});
            }
        }
    }

    @Override
    public void applyTo(HttpCallData step) {
        step.setName(nameField.getText().trim());
        step.setAssignResultToVar(assignResultField.getText().trim());
        step.setUrl(urlField.getText().trim());
        step.setMethod((String) methodBox.getSelectedItem());
        step.setContentType(blankToNull(contentTypeField.getText()));
        step.setTimeoutMs(parseIntSafe(timeoutField.getText(), 0));
        step.setFailOnError(failOnErrorBox.isSelected());
        step.setStatusCodeVar(blankToNull(statusCodeVarField.getText()));
        step.setResponseHeadersVar(blankToNull(responseHeadersVarField.getText()));
        step.setBody(blankToNull(bodyArea.getText()));

        List<VariableAssignment> headers = new ArrayList<>();
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String name = str(headersModel.getValueAt(i, 0));
            String val  = str(headersModel.getValueAt(i, 1));
            if (!name.isBlank()) headers.add(new VariableAssignment(name, val));
        }
        step.setHeaders(headers);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
