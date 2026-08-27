package ai.mindconnect.workflow.swing.ui;

import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Swing panel that renders a PlantUML diagram locally using the embedded
 * PlantUML library (no internet connection required).
 *
 * <p>Rendering runs on a virtual thread so the Swing EDT stays responsive.
 * A loading indicator is shown while the diagram is being generated; errors
 * are displayed as a message inside the panel.
 *
 * <p>The panel provides a zoom slider and drag-to-scroll navigation.
 */
public class PumlDiagramPanel extends JPanel {

    // -----------------------------------------------------------------------
    // UI components
    // -----------------------------------------------------------------------

    private final JLabel       imageLabel = new JLabel("(no diagram yet)", SwingConstants.CENTER);
    private final JScrollPane  scroll     = new JScrollPane(imageLabel);
    private final JLabel       statusLabel = new JLabel(" ");
    private final JSlider      zoomSlider  = new JSlider(25, 300, 100);
    private final JButton      refreshBtn  = new JButton("Refresh");

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private String        currentPuml    = null;
    private BufferedImage rawImage       = null;
    /** Incremented on every render request so stale completions are discarded. */
    private volatile int  renderGeneration = 0;

    /** Drag-to-scroll anchor. */
    private Point dragOrigin = null;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PumlDiagramPanel() {
        setLayout(new BorderLayout(2, 2));

        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        imageLabel.setForeground(Color.GRAY);

        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        // Drag-to-scroll the viewport
        scroll.getViewport().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { dragOrigin = e.getPoint(); }
            @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
        });
        scroll.getViewport().addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) return;
                JViewport vp   = scroll.getViewport();
                Point     vp0  = vp.getViewPosition();
                int dx = dragOrigin.x - e.getX();
                int dy = dragOrigin.y - e.getY();
                Rectangle view = new Rectangle(
                        Math.max(0, vp0.x + dx),
                        Math.max(0, vp0.y + dy),
                        vp.getWidth(), vp.getHeight());
                imageLabel.scrollRectToVisible(view);
                dragOrigin = e.getPoint();
            }
        });

        add(scroll, BorderLayout.CENTER);

        // Toolbar: Refresh | Zoom controls
        zoomSlider.setMajorTickSpacing(25);
        zoomSlider.setPaintTicks(true);
        zoomSlider.setSnapToTicks(false);
        zoomSlider.setPreferredSize(new Dimension(160, zoomSlider.getPreferredSize().height));
        zoomSlider.addChangeListener(e -> applyZoom());

        JButton zoomIn  = smallButton("+");
        JButton zoomOut = smallButton("-");
        JButton zoom100 = smallButton("100%");
        zoomIn .setToolTipText("Zoom in");
        zoomOut.setToolTipText("Zoom out");
        zoom100.setToolTipText("Reset zoom to 100%");
        zoomIn .addActionListener(e -> { zoomSlider.setValue(Math.min(300, zoomSlider.getValue() + 10)); applyZoom(); });
        zoomOut.addActionListener(e -> { zoomSlider.setValue(Math.max(25,  zoomSlider.getValue() - 10)); applyZoom(); });
        zoom100.addActionListener(e -> { zoomSlider.setValue(100);                                        applyZoom(); });

        refreshBtn.setFocusable(false);
        refreshBtn.setToolTipText("Re-render diagram");
        refreshBtn.addActionListener(e -> renderAsync(currentPuml));

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setForeground(Color.GRAY);

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorderPainted(false);
        tb.add(refreshBtn);
        tb.addSeparator();
        tb.add(new JLabel("Zoom:"));
        tb.add(zoomOut);
        tb.add(zoomSlider);
        tb.add(zoomIn);
        tb.add(zoom100);
        tb.addSeparator();
        tb.add(statusLabel);

        add(tb, BorderLayout.NORTH);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Renders the supplied PUML source into a diagram image.
     * Ignored if {@code puml} is null/blank.
     */
    public void render(String puml) {
        if (puml == null || puml.isBlank()) {
            setPlaceholder("(empty diagram)");
            return;
        }
        renderAsync(puml);
    }

    // -----------------------------------------------------------------------
    // Async rendering
    // -----------------------------------------------------------------------

    private void renderAsync(String puml) {
        if (puml == null || puml.isBlank()) return;
        currentPuml = puml;
        int generation = ++renderGeneration;

        setStatus("Rendering...");
        imageLabel.setIcon(null);
        imageLabel.setText("Rendering...");
        refreshBtn.setEnabled(false);

        Thread.ofVirtual().start(() -> {
            try {
                BufferedImage img = renderPuml(puml);
                SwingUtilities.invokeLater(() -> {
                    if (generation != renderGeneration) return; // stale
                    rawImage = img;
                    applyZoom();
                    setStatus(img.getWidth() + " x " + img.getHeight() + " px");
                    refreshBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation != renderGeneration) return;
                    imageLabel.setIcon(null);
                    imageLabel.setText("<html><center><b>Could not render diagram</b><br>"
                            + "<small>" + escapeHtml(ex.getMessage()) + "</small></center></html>");
                    setStatus("Error: " + ex.getMessage());
                    refreshBtn.setEnabled(true);
                });
            }
        });
    }

    // -----------------------------------------------------------------------
    // PlantUML rendering
    // -----------------------------------------------------------------------

    private static BufferedImage renderPuml(String puml) throws Exception {
        SourceStringReader reader = new SourceStringReader(puml);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        reader.outputImage(baos, new FileFormatOption(FileFormat.PNG));
        byte[] pngBytes = baos.toByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) throw new Exception("PlantUML produced no image output");
        return img;
    }

    // -----------------------------------------------------------------------
    // Zoom
    // -----------------------------------------------------------------------

    private void applyZoom() {
        if (rawImage == null) return;
        double scale = zoomSlider.getValue() / 100.0;
        int w = (int) Math.round(rawImage.getWidth()  * scale);
        int h = (int) Math.round(rawImage.getHeight() * scale);
        Image scaled = rawImage.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        imageLabel.setIcon(new ImageIcon(scaled));
        imageLabel.setText(null);
        zoomSlider.setToolTipText("Zoom: " + zoomSlider.getValue() + "%");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setStatus(String msg) {
        statusLabel.setText(" " + msg);
    }

    private void setPlaceholder(String msg) {
        rawImage = null;
        imageLabel.setIcon(null);
        imageLabel.setText(msg);
        setStatus("");
    }

    private static JButton smallButton(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 5, 1, 5));
        return b;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
