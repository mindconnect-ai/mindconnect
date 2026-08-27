package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.swing.ui.AddStepDialog;
import ai.mindconnect.workflow.swing.ui.StepBlockRenderer;
import ai.mindconnect.workflow.swing.ui.StepEditorDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable panel that displays and edits the child-step list of any container
 * step (BlockData, ForEachData, the then/else blocks inside IfData, …).
 *
 * <p>Call {@link #load(List)} to populate from a step list, and
 * {@link #collectSteps()} to retrieve the current list back.
 *
 * <p>Double-clicking a step (or pressing the Edit button) opens a
 * {@link StepEditorDialog} for that child step, mirroring the behaviour of
 * the top-level step list in the main frame.
 */
public class StepsTablePanel extends JPanel {

    private final EditorPanelFactory factory;
    private final DefaultListModel<StepData> listModel = new DefaultListModel<>();
    private final JList<StepData> list = new JList<>(listModel);

    public StepsTablePanel(String title, EditorPanelFactory factory) {
        this.factory = factory;

        setLayout(new BorderLayout(2, 2));
        setBorder(BorderFactory.createTitledBorder(title));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new StepBlockRenderer());
        list.setFixedCellHeight(-1);

        // Double-click opens editor
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });

        add(new JScrollPane(list), BorderLayout.CENTER);

        // Toolbar buttons
        JButton addBtn    = smallButton("+");
        JButton editBtn   = smallButton("Edit");
        JButton removeBtn = smallButton("Del");
        JButton upBtn     = smallButton("Up");
        JButton downBtn   = smallButton("Dn");

        addBtn.setToolTipText("Add step");
        editBtn.setToolTipText("Edit selected step (double-click)");
        removeBtn.setToolTipText("Remove selected step");
        upBtn.setToolTipText("Move up");
        downBtn.setToolTipText("Move down");

        addBtn.addActionListener(e    -> addStep());
        editBtn.addActionListener(e   -> editSelected());
        removeBtn.addActionListener(e -> removeSelected());
        upBtn.addActionListener(e     -> moveSelected(-1));
        downBtn.addActionListener(e   -> moveSelected(1));

        list.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int idx  = list.getSelectedIndex();
            boolean has = idx >= 0;
            editBtn.setEnabled(has);
            removeBtn.setEnabled(has);
            upBtn.setEnabled(idx > 0);
            downBtn.setEnabled(has && idx < listModel.size() - 1);
        });
        editBtn.setEnabled(false);
        removeBtn.setEnabled(false);
        upBtn.setEnabled(false);
        downBtn.setEnabled(false);

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorderPainted(false);
        tb.setBackground(getBackground());
        tb.add(addBtn);
        tb.add(editBtn);
        tb.add(removeBtn);
        tb.addSeparator();
        tb.add(upBtn);
        tb.add(downBtn);
        add(tb, BorderLayout.SOUTH);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Replaces the list content with {@code steps} (may be null/empty). */
    public void load(List<StepData> steps) {
        listModel.clear();
        if (steps != null) steps.forEach(listModel::addElement);
    }

    /** Returns a mutable copy of the current step list. */
    public List<StepData> collectSteps() {
        List<StepData> out = new ArrayList<>(listModel.size());
        for (int i = 0; i < listModel.size(); i++) out.add(listModel.get(i));
        return out;
    }

    // -----------------------------------------------------------------------
    // Internal actions
    // -----------------------------------------------------------------------

    private void addStep() {
        Window win = SwingUtilities.getWindowAncestor(this);
        Frame owner = (win instanceof Frame f) ? f : null;
        AddStepDialog dlg = new AddStepDialog(owner);
        dlg.setVisible(true);
        StepData step = dlg.getResult();
        if (step == null) return;
        int idx = list.getSelectedIndex();
        int insertAt = (idx < 0) ? listModel.size() : idx + 1;
        listModel.insertElementAt(step, Math.min(insertAt, listModel.size()));
        list.setSelectedIndex(Math.min(insertAt, listModel.size() - 1));
    }

    private void editSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) return;
        StepData step = listModel.get(idx);
        Window win = SwingUtilities.getWindowAncestor(this);
        StepEditorDialog dlg = new StepEditorDialog(win, step, factory);
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            // Replace the element so the list renderer refreshes
            listModel.set(idx, step);
        }
    }

    private void removeSelected() {
        int idx = list.getSelectedIndex();
        if (idx >= 0) listModel.remove(idx);
    }

    private void moveSelected(int delta) {
        int idx = list.getSelectedIndex();
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= listModel.size()) return;
        StepData a = listModel.get(idx);
        StepData b = listModel.get(target);
        listModel.set(idx, b);
        listModel.set(target, a);
        list.setSelectedIndex(target);
    }

    private static JButton smallButton(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 4, 1, 4));
        return b;
    }
}
