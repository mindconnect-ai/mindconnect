package ai.mindconnect.workflow.swing.ui;

import javax.swing.*;

/**
 * Entry point for the Workflow Designer Swing application.
 *
 * <p>Run directly:
 * <pre>{@code
 *   java -jar mc-workflow-swing-ui-jar-with-dependencies.jar
 * }</pre>
 */
public class WorkflowDesignerApp {

    public static void main(String[] args) {
        // Use the system look-and-feel (Aqua on macOS) so that native OS
        // behaviours work correctly — in particular Cmd+C/V/X copy/paste on macOS.
        // Nimbus was previously used to work around font-clipping of emoji characters,
        // but all emoji have since been replaced with plain ASCII labels, so the
        // system L&F is safe to use again.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to default Metal L&F
        }

        SwingUtilities.invokeLater(() -> {
            WorkflowDesignerFrame frame = new WorkflowDesignerFrame();
            frame.setVisible(true);
        });
    }
}
