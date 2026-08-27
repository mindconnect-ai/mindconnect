package ai.mindconnect.workflow.swing.step;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.IfData;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for {@link IfData}.
 *
 * <p>The step has N conditions, each with a boolean expression and a
 * {@link BlockData} of "then" steps.  There is also an optional else block.
 *
 * <p>The UI renders each condition as a row in a scrollable panel:
 * <pre>
 *  ┌────────────────────────────────────────────────────┐
 *  │  Step name: [____]   Assign result to: [____]      │
 *  ├────────────────────────────────────────────────────┤
 *  │  Condition 1: [expression_______________] [Remove] │
 *  │  ┌─ Then steps ───────────────────────────────┐   │
 *  │  │  …StepsTablePanel…                         │   │
 *  │  └────────────────────────────────────────────┘   │
 *  │  [+ Add Condition]                                 │
 *  ├────────────────────────────────────────────────────┤
 *  │  ┌─ Else steps ───────────────────────────────┐   │
 *  │  │  …StepsTablePanel…                         │   │
 *  │  └────────────────────────────────────────────┘   │
 *  └────────────────────────────────────────────────────┘
 * </pre>
 */
public class IfEditorPanel extends StepEditorPanel<IfData> {

    // -----------------------------------------------------------------------
    // Inner class: one condition row (expression field + then-steps panel)
    // -----------------------------------------------------------------------

    private final class ConditionRow {
        final JTextField exprField = new JTextField();
        final StepsTablePanel thenPanel = new StepsTablePanel("Then Steps", factory);
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final EditorPanelFactory factory;
    private final JTextField nameField         = new JTextField();
    private final JTextField assignResultField  = new JTextField();

    /** The vertical panel that holds all condition rows. */
    private final JPanel conditionsPanel = new JPanel();
    private final List<ConditionRow> conditionRows = new ArrayList<>();

    /** Else block — always present. */
    private final StepsTablePanel elsePanel;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public IfEditorPanel(EditorPanelFactory factory) {
        this.factory = factory;
        elsePanel = new StepsTablePanel("Else Steps", factory);

        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Header: name + assignResult
        JPanel header = new JPanel(new GridLayout(2, 2, 4, 4));
        header.add(new JLabel("Step name:"));        header.add(nameField);
        header.add(new JLabel("Assign result to:")); header.add(assignResultField);
        add(header, BorderLayout.NORTH);

        // Centre: conditions (scrollable) + else block
        conditionsPanel.setLayout(new BoxLayout(conditionsPanel, BoxLayout.Y_AXIS));

        JButton addCondBtn = new JButton("+ Add Condition");
        addCondBtn.setFocusable(false);
        addCondBtn.addActionListener(e -> {
            addConditionRow(new IfData.Condition());
            revalidate();
            repaint();
        });

        JPanel condWrapper = new JPanel(new BorderLayout(0, 4));
        condWrapper.add(new JScrollPane(conditionsPanel), BorderLayout.CENTER);
        JPanel addBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        addBtnRow.add(addCondBtn);
        condWrapper.add(addBtnRow, BorderLayout.SOUTH);
        condWrapper.setBorder(BorderFactory.createTitledBorder("Conditions (evaluated top to bottom)"));

        // else panel is fixed-height at the bottom
        elsePanel.setPreferredSize(new Dimension(0, 180));

        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT, condWrapper, elsePanel);
        centre.setResizeWeight(0.7);
        add(centre, BorderLayout.CENTER);
    }

    // -----------------------------------------------------------------------
    // StepEditorPanel contract
    // -----------------------------------------------------------------------

    @Override
    public void populate(IfData step) {
        nameField.setText(nvl(step.getName()));
        assignResultField.setText(nvl(step.getAssignResultToVar()));

        // Clear existing rows
        conditionsPanel.removeAll();
        conditionRows.clear();

        // Populate one row per condition
        if (step.getConditions() != null) {
            for (IfData.Condition c : step.getConditions()) {
                addConditionRow(c);
            }
        }
        // Always have at least one condition row to guide the user
        if (conditionRows.isEmpty()) {
            addConditionRow(new IfData.Condition());
        }

        // Else block
        elsePanel.load(step.getElseBlock() != null ? step.getElseBlock().getSteps() : null);

        revalidate();
        repaint();
    }

    @Override
    public void applyTo(IfData step) {
        step.setName(nameField.getText().trim());
        step.setAssignResultToVar(blankToNull(assignResultField.getText()));

        List<IfData.Condition> conditions = new ArrayList<>();
        for (ConditionRow row : conditionRows) {
            String expr = row.exprField.getText().trim();
            if (expr.isBlank()) continue;          // skip empty/blank condition rows
            IfData.Condition c = new IfData.Condition();
            c.setCondition(expr);
            BlockData thenBlock = new BlockData();
            thenBlock.setSteps(row.thenPanel.collectSteps());
            c.setThenBlock(thenBlock);
            conditions.add(c);
        }
        step.setConditions(conditions.toArray(new IfData.Condition[0]));

        // Else block — only set if there are actually steps
        List<?> elseSteps = elsePanel.collectSteps();
        if (!elseSteps.isEmpty()) {
            BlockData elseBlock = new BlockData();
            elseBlock.setSteps(elsePanel.collectSteps());
            step.setElseBlock(elseBlock);
        } else {
            step.setElseBlock(null);
        }
    }

    // -----------------------------------------------------------------------
    // Condition row management
    // -----------------------------------------------------------------------

    private void addConditionRow(IfData.Condition condition) {
        ConditionRow row = new ConditionRow();
        if (condition.getCondition() != null) {
            row.exprField.setText(condition.getCondition());
        }
        if (condition.getThenBlock() != null) {
            row.thenPanel.load(condition.getThenBlock().getSteps());
        }
        conditionRows.add(row);

        // Build the visual row panel
        JPanel rowPanel = new JPanel(new BorderLayout(4, 4));
        rowPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
                BorderFactory.createLineBorder(new Color(180, 180, 180), 1, true)
        ));
        rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));

        // Expression header bar
        JButton removeBtn = new JButton("Remove");
        removeBtn.setFocusable(false);
        removeBtn.setMargin(new Insets(1, 4, 1, 4));
        removeBtn.addActionListener(e -> {
            conditionRows.remove(row);
            conditionsPanel.remove(rowPanel);
            conditionsPanel.revalidate();
            conditionsPanel.repaint();
        });

        JPanel exprBar = new JPanel(new BorderLayout(4, 0));
        exprBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        exprBar.add(new JLabel("Condition:"), BorderLayout.WEST);
        exprBar.add(row.exprField, BorderLayout.CENTER);
        exprBar.add(removeBtn, BorderLayout.EAST);

        row.thenPanel.setPreferredSize(new Dimension(0, 150));

        rowPanel.add(exprBar, BorderLayout.NORTH);
        rowPanel.add(row.thenPanel, BorderLayout.CENTER);

        conditionsPanel.add(rowPanel);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String nvl(String s) { return s != null ? s : ""; }
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
