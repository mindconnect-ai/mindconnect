package ai.mindconnect.workflow.swing.ui;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.swing.step.EditorPanelFactory;
import ai.mindconnect.workflow.swing.step.StepEditorPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Modal dialog that hosts a {@link StepEditorPanel} for editing a child step
 * in-place (i.e. when the step lives inside a container such as a
 * {@link BlockData} or
 * {@link ForEachData}).
 *
 * <p>Usage:
 * <pre>{@code
 * StepEditorDialog dlg = new StepEditorDialog(ownerWindow, step, factory);
 * dlg.setVisible(true);          // blocks until OK / Cancel
 * if (dlg.isConfirmed()) { ... } // step was mutated in-place on OK
 * }</pre>
 *
 * <p>On <b>OK</b> the dialog calls {@code panel.applyTo(step)} and sets the
 * confirmed flag.  On <b>Cancel</b> or window-close the step is left
 * unchanged.
 *
 * <p>Keyboard shortcuts: <kbd>Enter</kbd> on the OK button confirms;
 * <kbd>Escape</kbd> cancels.
 */
public class StepEditorDialog extends JDialog {

    private boolean confirmed = false;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public StepEditorDialog(Window owner, StepData step, EditorPanelFactory factory) {
        super(owner, titleFor(step), ModalityType.APPLICATION_MODAL);

        StepEditorPanel panel = factory.createFor(step);
        panel.populate(step);

        // OK / Cancel bar
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        okBtn.setPreferredSize(cancelBtn.getPreferredSize());

        okBtn.addActionListener(e -> {
            panel.applyTo(step);
            confirmed = true;
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(okBtn);

        // Escape → cancel
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel",
                new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); }
                });

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.add(okBtn);
        buttons.add(cancelBtn);

        getContentPane().setLayout(new BorderLayout(4, 4));
        getContentPane().add(panel, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        setSize(680, 580);
        setMinimumSize(new Dimension(480, 380));
        setLocationRelativeTo(owner);
    }

    /** Returns {@code true} if the user clicked OK (step has been mutated). */
    public boolean isConfirmed() { return confirmed; }

    private static String titleFor(StepData step) {
        String name = step.getName();
        String type = step.getType();
        return "Edit Step — " + (name != null && !name.isBlank() ? name : type);
    }
}
