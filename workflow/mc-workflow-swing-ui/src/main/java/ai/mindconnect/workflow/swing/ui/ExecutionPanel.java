package ai.mindconnect.workflow.swing.ui;

import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.execution.StepExecutionInfo;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.Writer;

/**
 * Panel that shows execution output: status banner, result value, and per-step logs.
 */
public class ExecutionPanel extends JPanel {

    private final JTextPane outputPane = new JTextPane();
    private final StyledDocument doc;

    // Styles
    private final Style successStyle;
    private final Style errorStyle;
    private final Style infoStyle;
    private final Style stepStyle;
    private final Style logStyle;
    private final Style resultStyle;

    public ExecutionPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Execution Output"));

        outputPane.setEditable(false);
        outputPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        doc = outputPane.getStyledDocument();

        successStyle = addStyle("success", Color.decode("#1a7a1a"), true);
        errorStyle   = addStyle("error",   Color.decode("#aa0000"), true);
        infoStyle    = addStyle("info",    Color.decode("#555555"), false);
        stepStyle    = addStyle("step",    Color.decode("#0050a0"), true);
        logStyle     = addStyle("log",     Color.decode("#333333"), false);
        resultStyle  = addStyle("result",  Color.decode("#006060"), false);

        JScrollPane scroll = new JScrollPane(outputPane);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scroll, BorderLayout.CENTER);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clear());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        south.add(clearBtn);
        add(south, BorderLayout.SOUTH);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void clear() {
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}
    }

    public void showRunning() {
        clear();
        append("Running workflow...\n\n", infoStyle);
    }

    public void showResult(WorkflowResult result) {
        if (result.isSuccess()) {
            append("SUCCESS\n", successStyle);
            Object res = result.getResult();
            append("Result: " + (res != null ? res.toString() : "(null)") + "\n\n", resultStyle);
        } else if (result.isError()) {
            append("ERROR\n", errorStyle);
            if (result.getError() != null) {
                append(result.getError().getMessage() + "\n\n", errorStyle);
            }
        } else {
            append("HALTED\n\n", infoStyle);
        }
        appendStepLogs(result);
        scrollToBottom();
    }

    public void showError(String message) {
        append("ERROR: " + message + "\n", errorStyle);
        scrollToBottom();
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private void appendStepLogs(WorkflowResult result) {
        if (result.getInstance() == null) return;

        append("--- Step execution ---\n", infoStyle);
        for (StepInstance<?> step : result.getInstance().getStepInstances()) {
            StepExecutionInfo info = step.getStepExecutionInfo();
            if (info == null) continue;

            String stepName = step.getConfig() != null
                    ? (step.getConfig().getName() != null ? step.getConfig().getName() : step.getConfig().getType())
                    : "?";

            // Build the result suffix:  → varName = <value>  or  → <value>  or nothing
            String resultSuffix = buildResultSuffix(step);

            append("  [" + stepName + "] "
                    + info.getState() + "  (" + info.getDurationInMs() + "ms)"
                    + resultSuffix + "\n", stepStyle);

            if (info.getLogs() != null) {
                for (var entry : info.getLogs()) {
                    if ("DEBUG".equals(entry.getLevel())) continue; // skip verbose debug lines
                    append("    " + entry.getLevel() + ": " + entry.getMessage() + "\n", logStyle);
                }
            }
            if (info.getErrorMessage() != null && !info.getErrorMessage().isBlank()) {
                append("    ERROR: " + info.getErrorMessage() + "\n", errorStyle);
            }
        }
    }

    private static String buildResultSuffix(StepInstance<?> step) {
        Object resultVal = step.getResult();
        if (resultVal == null) return "";

        String preview = resultPreview(resultVal);
        String varName = step.getConfig() != null ? step.getConfig().getAssignResultToVar() : null;

        if (varName != null && !varName.isBlank()) {
            return "  ->  " + varName + " = " + preview;
        }
        return "  ->  " + preview;
    }

    private static String resultPreview(Object val) {
        String s = String.valueOf(val);
        // Collapse whitespace (e.g. multi-line JSON) into a single line for readability
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private void append(String text, Style style) {
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {}
    }

    private Style addStyle(String name, Color color, boolean bold) {
        Style s = outputPane.addStyle(name, null);
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        return s;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> outputPane.setCaretPosition(doc.getLength()));
    }

    // -----------------------------------------------------------------------
    // Script output writer
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Writer} that appends text directly into this panel's
     * output area.  Pass this to {@link WorkflowContextFactory#setScriptOutputWriter}
     * so that {@code print()} / {@code println()} calls in code steps are
     * displayed here rather than on {@code System.out}.
     */
    public Writer createScriptOutputWriter() {
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                String text = new String(cbuf, off, len);
                // Append on the EDT; safe to call from any thread
                SwingUtilities.invokeLater(() -> ExecutionPanel.this.append(text, logStyle));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
    }
}
