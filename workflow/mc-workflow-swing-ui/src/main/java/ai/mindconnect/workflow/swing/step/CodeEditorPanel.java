package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.scripting.ScriptExecutor;

import javax.swing.*;
import java.awt.*;

/**
 * Editor for {@link CodeData} — shows language selector and a multi-line code area.
 *
 * <p>The language dropdown is populated from the set of language names that are
 * actually registered in the application's {@link ScriptExecutor},
 * so it always reflects what the runtime can execute.
 *
 * <p>If an existing step references a language that is not in the registered set
 * (e.g. loaded from a file), that language is added as an extra item so the
 * round-trip is lossless.
 */
public class CodeEditorPanel extends StepEditorPanel<CodeData> {

    private final JTextField nameField = new JTextField();
    private final JTextField assignResultField = new JTextField();
    private final JComboBox<String> languageBox;
    private final JTextArea codeArea = new JTextArea();
    private final JCheckBox wrapInFunctionBox = new JCheckBox("Wrap in function");
    private final JCheckBox injectVarsBox = new JCheckBox("Inject variables", true);
    private final JCheckBox exportVarsBox = new JCheckBox("Export variables", true);

    /**
     * @param registeredLanguages the language names returned by
     *        {@code ScriptExecutor.getRegisteredLanguages()} — must not be empty
     */
    public CodeEditorPanel(java.util.Collection<String> registeredLanguages) {
        // Sort for a stable, readable order
        String[] langs = registeredLanguages.stream().sorted().toArray(String[]::new);
        languageBox = new JComboBox<>(langs);
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Top: metadata
        JPanel top = new JPanel(new GridLayout(4, 2, 4, 4));
        top.add(new JLabel("Step name:"));        top.add(nameField);
        top.add(new JLabel("Assign result to:")); top.add(assignResultField);
        top.add(new JLabel("Language:"));         top.add(languageBox);
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        flags.add(wrapInFunctionBox);
        flags.add(injectVarsBox);
        flags.add(exportVarsBox);
        top.add(new JLabel("Options:"));          top.add(flags);
        add(top, BorderLayout.NORTH);

        // Centre: code editor
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        codeArea.setTabSize(4);
        JScrollPane scroll = new JScrollPane(codeArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Code"));
        add(scroll, BorderLayout.CENTER);
    }

    @Override
    public void populate(CodeData step) {
        nameField.setText(nvl(step.getName()));
        assignResultField.setText(nvl(step.getAssignResultToVar()));

        String lang = step.getLanguage();
        if (lang != null && !lang.isBlank()) {
            // Add the language if it's not already in the dropdown (loaded from file, etc.)
            boolean found = false;
            for (int i = 0; i < languageBox.getItemCount(); i++) {
                if (lang.equals(languageBox.getItemAt(i))) { found = true; break; }
            }
            if (!found) languageBox.addItem(lang);
            languageBox.setSelectedItem(lang);
        } else if (languageBox.getItemCount() > 0) {
            languageBox.setSelectedIndex(0);
        }

        codeArea.setText(nvl(step.getCode()));
        codeArea.setCaretPosition(0);
        wrapInFunctionBox.setSelected(step.isWrapInFunction());
        injectVarsBox.setSelected(step.isInjectVariables());
        exportVarsBox.setSelected(step.isExportVariables());
    }

    @Override
    public void applyTo(CodeData step) {
        step.setName(nameField.getText().trim());
        step.setAssignResultToVar(assignResultField.getText().trim());
        step.setLanguage((String) languageBox.getSelectedItem());
        step.setCode(codeArea.getText());
        step.setWrapInFunction(wrapInFunctionBox.isSelected());
        step.setInjectVariables(injectVarsBox.isSelected());
        step.setExportVariables(exportVarsBox.isSelected());
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
