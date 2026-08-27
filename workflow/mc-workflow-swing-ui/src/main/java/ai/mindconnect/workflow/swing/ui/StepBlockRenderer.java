package ai.mindconnect.workflow.swing.ui;

import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.StepData;

import javax.swing.*;
import java.awt.*;

/**
 * Renders each workflow step as a coloured block in the step list.
 *
 * <p>Each step type gets a distinctive background colour so the sequence
 * is easy to scan at a glance.
 */
public class StepBlockRenderer extends JPanel implements ListCellRenderer<StepData> {

    private final JLabel iconLabel  = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();

    public StepBlockRenderer() {
        setLayout(new BorderLayout(6, 2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(3, 6, 3, 6),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0, 0, 0, 30), 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                )
        ));
        setOpaque(true);

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 11f));
        subtitleLabel.setForeground(new Color(60, 60, 60));

        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.PLAIN, 18f));
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 1));
        text.setOpaque(false);
        text.add(titleLabel);
        text.add(subtitleLabel);

        add(iconLabel, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(
            JList<? extends StepData> list, StepData step,
            int index, boolean isSelected, boolean cellHasFocus) {

        String type     = step.getType();
        String name     = step.getName() != null ? step.getName() : "";
        String subtitle = buildSubtitle(step);

        iconLabel.setText(iconFor(type));
        titleLabel.setText(name.isEmpty() ? "(" + type + ")" : name);
        subtitleLabel.setText(subtitle);

        Color bg = colorFor(type);
        setBackground(isSelected ? bg.darker() : bg);
        titleLabel.setForeground(isSelected ? Color.WHITE : Color.BLACK);
        subtitleLabel.setForeground(isSelected ? new Color(220, 220, 220) : new Color(60, 60, 60));

        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(3, 6, 3, 6),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(isSelected
                                ? bg.darker().darker()
                                : new Color(0, 0, 0, 30), 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                )
        ));

        return this;
    }

    private static String buildSubtitle(StepData step) {
        return switch (step) {
            case HttpCallData h -> h.getMethod() + " " + nvl(h.getUrl(), "(no url)");
            case CodeData c     -> nvl(c.getLanguage(), "script") + " | "
                    + preview(c.getCode(), 40);
            default -> {
                String r = step.getAssignResultToVar();
                yield r != null && !r.isBlank() ? "-> " + r : step.getType();
            }
        };
    }

    private static String iconFor(String type) {
        return switch (type) {
            case "httpcall", "http"        -> "[http]";
            case "code"                    -> "[code]";
            case "assignvariables"         -> "[set]";
            case "foreach"                 -> "[for]";
            case "if"                      -> "[if]";
            case "halt"                    -> "[stop]";
            case "jumpto"                  -> "[jump]";
            case "callworkflow"            -> "[call]";
            case "block"                   -> "[blk]";
            default                        -> "[?]";
        };
    }

    private static Color colorFor(String type) {
        return switch (type) {
            case "httpcall", "http"        -> new Color(209, 231, 255);
            case "code"                    -> new Color(220, 255, 220);
            case "assignvariables"         -> new Color(255, 249, 209);
            case "foreach"                 -> new Color(230, 215, 255);
            case "if"                      -> new Color(255, 225, 200);
            case "halt"                    -> new Color(255, 210, 210);
            case "jumpto"                  -> new Color(210, 245, 245);
            case "callworkflow"            -> new Color(220, 240, 220);
            default                        -> new Color(235, 235, 235);
        };
    }

    private static String nvl(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }

    private static String preview(String code, int max) {
        if (code == null || code.isBlank()) return "(empty)";
        String trimmed = code.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }
}
