package ai.mindconnect.workflow.swing.ui;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.dsl.puml.PumlWorkflowParser;
import ai.mindconnect.workflow.dsl.puml.PumlWorkflowSerializer;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.JsonWorkflowConfigurer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;

import ai.mindconnect.workflow.swing.model.WorkflowEditorModel;
import ai.mindconnect.workflow.swing.step.*;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * Main application window for the Workflow Designer.
 *
 * <p>Layout (left → right):
 * <pre>
 *  ┌─────────────────────────────────────────────────────┐
 *  │  Toolbar: New | Open | Save | ─── | Add | Remove    │
 *  │            Move Up | Move Down | ─── | Run          │
 *  ├────────────────────┬────────────────────────────────┤
 *  │                    │                                │
 *  │   Step sequence    │     Step detail editor         │
 *  │   (block list)     │                                │
 *  │                    │                                │
 *  ├────────────────────┴────────────────────────────────┤
 *  │              Execution output                        │
 *  └─────────────────────────────────────────────────────┘
 * </pre>
 */
public class WorkflowDesignerFrame extends JFrame {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final WorkflowEditorModel model = new WorkflowEditorModel();
    private final JacksonWorkflowSerializer serializer =
            new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());
    private final PumlWorkflowSerializer pumlSerializer = new PumlWorkflowSerializer();
    private final PumlWorkflowParser     pumlParser     = new PumlWorkflowParser();
    /** Shared context factory — holds the ScriptExecutor with all registered languages. */
    private final DefaultWorkflowContextFactory contextFactory = new DefaultWorkflowContextFactory();
    // Add json: expression support (json: {"key":"value"} → parsed Map/List/scalar)
    { new JsonWorkflowConfigurer().configure(contextFactory); }
    /** Shared editor panel factory — creates the right StepEditorPanel for any StepData. */
    private final EditorPanelFactory editorPanelFactory =
            new EditorPanelFactory(contextFactory.getScriptExecutor().getRegisteredLanguages());
    /** Execution output panel — also provides the writer for script print()/println(). */
    private final ExecutionPanel executionPanel = new ExecutionPanel();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "workflow-runner");
        t.setDaemon(true);
        return t;
    });

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(WorkflowDesignerFrame.class);
    private static final String PREF_LAST_FILE = "lastOpenedFile";

    private File currentFile = null;
    /** Values entered in the most recent run — pre-populated next time. */
    private Map<String, String> lastRunParams = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // UI components
    // -----------------------------------------------------------------------

    private final JList<StepData> stepList;
    private final JPanel detailHost;

    // Toolbar buttons kept as fields so we can enable/disable them
    private final JButton saveBtn   = toolButton("Save",    "Save (Ctrl+S)");
    private final JButton saveAsBtn = toolButton("Save As", "Save As");
    private final JButton removeBtn = toolButton("Remove",  "Remove selected step (Del)");
    private final JButton upBtn     = toolButton("Up",      "Move step up");
    private final JButton downBtn   = toolButton("Down",    "Move step down");
    private final JButton editBtn   = toolButton("Edit",    "Edit selected step (Enter)");
    private final JButton runBtn    = toolButton("Run",     "Execute workflow (F5)");

    // Workflow header fields
    private final JTextField wfNameField      = new JTextField(20);
    private final JTextField wfResultFromField = new JTextField(15);

    // Params table (declared parameters with defaults)
    private final DefaultTableModel paramsTableModel =
            new DefaultTableModel(new Object[]{"Parameter Name", "Default Value"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return true; }
            };

    // Bottom tabs: execution output + PUML source view + PUML diagram
    private final JTabbedPane    bottomTabs   = new JTabbedPane();
    private final JTextArea      pumlPane     = new JTextArea();
    private final PumlDiagramPanel pumlDiagramPanel = new PumlDiagramPanel();

    // Current step editor (swapped in detail panel)
    @SuppressWarnings("rawtypes")
    private StepEditorPanel currentEditor = null;
    private int currentEditorIndex = -1;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public WorkflowDesignerFrame() {
        super("Workflow Designer");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmAndClose(); }
        });

        // Route script print()/println() output to the execution panel
        contextFactory.setScriptOutputWriter(executionPanel.createScriptOutputWriter());

        // Step list
        stepList = new JList<>(model.getListModel());
        stepList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stepList.setCellRenderer(new StepBlockRenderer());
        stepList.setFixedCellHeight(-1);
        stepList.addListSelectionListener(this::onSelectionChanged);
        stepList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openEditor();
            }
        });

        // Detail host
        detailHost = new JPanel(new BorderLayout());
        detailHost.setBorder(BorderFactory.createTitledBorder("Step Details"));
        showEmptyDetail();

        // Main split: list | detail
        JSplitPane centreSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildStepListPanel(), detailHost);
        centreSplit.setDividerLocation(260);
        centreSplit.setResizeWeight(0.3);

        // Bottom tabs: execution output + PUML source view + PUML diagram
        pumlPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pumlPane.setEditable(false);
        pumlPane.setLineWrap(false);
        bottomTabs.addTab("Execution Output", executionPanel);
        bottomTabs.addTab("PUML Source", new JScrollPane(pumlPane));
        bottomTabs.addTab("PUML Diagram", pumlDiagramPanel);
        bottomTabs.addChangeListener(e -> {
            int idx = bottomTabs.getSelectedIndex();
            if (idx == 1) refreshPumlTab();
            else if (idx == 2) refreshDiagramTab();
        });

        // Main vertical split: centre | bottom tabs
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                centreSplit, bottomTabs);
        mainSplit.setDividerLocation(420);
        mainSplit.setResizeWeight(0.65);

        setLayout(new BorderLayout());
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildHeader(), BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);

        // Keyboard shortcuts
        bindKeys();

        // Model change listener → title + PUML tabs (when visible)
        model.addChangeListener(() -> {
            syncTitle();
            int idx = bottomTabs.getSelectedIndex();
            if (idx == 1) refreshPumlTab();
            else if (idx == 2) refreshDiagramTab();
        });

        setSize(1100, 750);
        setMinimumSize(new Dimension(800, 560));
        setLocationRelativeTo(null);
        syncTitle();
        updateButtonStates();

        // Restore last opened file
        restoreLastFile();
    }

    // -----------------------------------------------------------------------
    // UI builders
    // -----------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton newBtn       = toolButton("New",       "New workflow (Ctrl+N)");
        JButton openBtn      = toolButton("Open",      "Open JSON or PUML (Ctrl+O)");
        JButton saveAsPuml   = toolButton("Save PUML", "Save as PlantUML (.puml)");

        newBtn.addActionListener(e     -> newWorkflow());
        openBtn.addActionListener(e    -> openFile());
        saveBtn.addActionListener(e    -> saveFile(false));
        saveAsBtn.addActionListener(e  -> saveFile(true));
        saveAsPuml.addActionListener(e -> savePumlFile());

        JButton addBtn = toolButton("+ Add Step", "Add a step (Ins)");
        addBtn.addActionListener(e -> addStep());
        removeBtn.addActionListener(e -> removeSelectedStep());
        upBtn.addActionListener(e   -> moveStep(-1));
        downBtn.addActionListener(e -> moveStep(1));
        editBtn.addActionListener(e -> openEditor());
        runBtn.addActionListener(e  -> runWorkflow());
        runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD));

        tb.add(newBtn);    tb.add(openBtn);
        tb.add(saveBtn);   tb.add(saveAsBtn);  tb.add(saveAsPuml);
        tb.addSeparator();
        tb.add(addBtn);    tb.add(removeBtn);
        tb.addSeparator();
        tb.add(upBtn);     tb.add(downBtn);
        tb.addSeparator();
        tb.add(editBtn);
        tb.addSeparator();
        tb.add(runBtn);

        return tb;
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));

        // Top row: name + resultFrom + Apply button
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        wfNameField.addActionListener(e -> applyHeaderFields());
        wfResultFromField.addActionListener(e -> applyHeaderFields());
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> applyHeaderFields());
        topRow.add(new JLabel("Workflow name:"));  topRow.add(wfNameField);
        topRow.add(Box.createHorizontalStrut(8));
        topRow.add(new JLabel("Result from var:")); topRow.add(wfResultFromField);
        topRow.add(apply);
        p.add(topRow, BorderLayout.NORTH);

        // Bottom row: declared parameters table (name + default value)
        JTable paramsTable = new JTable(paramsTableModel);
        paramsTable.setRowHeight(22);
        paramsTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane paramsScroll = new JScrollPane(paramsTable);
        paramsScroll.setPreferredSize(new Dimension(0, 90));

        JButton addParam = new JButton("+ Param");
        JButton delParam = new JButton("- Param");
        addParam.setFocusable(false);
        delParam.setFocusable(false);
        addParam.addActionListener(e -> {
            paramsTableModel.addRow(new Object[]{"", ""});
            int row = paramsTableModel.getRowCount() - 1;
            paramsTable.setRowSelectionInterval(row, row);
            paramsTable.scrollRectToVisible(paramsTable.getCellRect(row, 0, true));
        });
        delParam.addActionListener(e -> {
            if (paramsTable.isEditing()) paramsTable.getCellEditor().stopCellEditing();
            int row = paramsTable.getSelectedRow();
            if (row >= 0) paramsTableModel.removeRow(row);
        });

        JPanel paramBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        paramBtns.add(addParam);
        paramBtns.add(delParam);

        JPanel paramsPanel = new JPanel(new BorderLayout(2, 0));
        paramsPanel.setBorder(BorderFactory.createTitledBorder("Parameters (declared inputs with optional defaults)"));
        paramsPanel.add(paramsScroll, BorderLayout.CENTER);
        paramsPanel.add(paramBtns, BorderLayout.EAST);
        p.add(paramsPanel, BorderLayout.CENTER);

        populateHeaderFields();
        return p;
    }

    private JPanel buildStepListPanel() {
        JScrollPane scroll = new JScrollPane(stepList);
        scroll.setPreferredSize(new Dimension(250, 0));

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Steps"));
        p.add(scroll, BorderLayout.CENTER);

        // Drag-to-reorder hint
        JLabel hint = new JLabel(" Double-click or press Enter to edit");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(Color.GRAY);
        p.add(hint, BorderLayout.SOUTH);

        return p;
    }

    private void showEmptyDetail() {
        detailHost.removeAll();
        JLabel l = new JLabel("Select a step to edit or click '+ Add Step'");
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setForeground(Color.GRAY);
        detailHost.add(l, BorderLayout.CENTER);
        currentEditor = null;
        currentEditorIndex = -1;
        detailHost.revalidate();
        detailHost.repaint();
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private void newWorkflow() {
        if (!confirmDiscard()) return;
        model.load(new WorkflowData());
        model.getWorkflow().setName("untitled");
        currentFile = null;
        PREFS.remove(PREF_LAST_FILE);
        lastRunParams = new LinkedHashMap<>();
        populateHeaderFields();
        showEmptyDetail();
        syncTitle();
    }

    private void openFile() {
        if (!confirmDiscard()) return;
        JFileChooser fc = workflowFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            WorkflowData wf = isPuml(f) ? pumlParser.parse(f.toPath()) : serializer.read(f.toPath());
            model.load(wf);
            currentFile = f;
            PREFS.put(PREF_LAST_FILE, f.getAbsolutePath());
            lastRunParams = new LinkedHashMap<>();
            populateHeaderFields();
            showEmptyDetail();
            syncTitle();
        } catch (Exception ex) {
            showError("Failed to load workflow", ex);
        }
    }

    private void saveFile(boolean forceChooser) {
        applyHeaderFields();
        if (currentEditor != null) commitCurrentEditor();

        if (forceChooser || currentFile == null) {
            JFileChooser fc = workflowFileChooser();
            if (currentFile != null) fc.setSelectedFile(currentFile);
            else fc.setSelectedFile(new File(model.getName() + ".json"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File chosen = fc.getSelectedFile();
            // If the chosen file has no extension, fall back to .json
            currentFile = (isPuml(chosen) ? ensurePumlExtension(chosen) : ensureJsonExtension(chosen));
        }
        try {
            if (isPuml(currentFile)) {
                String puml = pumlSerializer.serialize(model.getWorkflow());
                java.nio.file.Files.writeString(currentFile.toPath(), puml);
            } else {
                serializer.write(model.getWorkflow(), currentFile.toPath());
            }
            PREFS.put(PREF_LAST_FILE, currentFile.getAbsolutePath());
            model.clearDirty();
            syncTitle();
        } catch (Exception ex) {
            showError("Failed to save workflow", ex);
        }
    }

    private void savePumlFile() {
        applyHeaderFields();
        if (currentEditor != null) commitCurrentEditor();

        JFileChooser fc = pumlFileChooser();
        String defaultName = model.getName() != null ? model.getName() : "workflow";
        fc.setSelectedFile(currentFile != null && isPuml(currentFile)
                ? currentFile
                : new File(defaultName + ".puml"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ensurePumlExtension(fc.getSelectedFile());
        try {
            String puml = pumlSerializer.serialize(model.getWorkflow());
            java.nio.file.Files.writeString(f.toPath(), puml);
            currentFile = f;
            PREFS.put(PREF_LAST_FILE, f.getAbsolutePath());
            model.clearDirty();
            syncTitle();
            // Switch to PUML tab so the user sees the saved output
            bottomTabs.setSelectedIndex(1);
            refreshPumlTab();
        } catch (Exception ex) {
            showError("Failed to save PUML file", ex);
        }
    }

    private void addStep() {
        AddStepDialog dlg = new AddStepDialog(this);
        dlg.setVisible(true);
        StepData step = dlg.getResult();
        if (step == null) return;
        int idx = stepList.getSelectedIndex();
        model.insertStepAfter(idx, step);
        int newIdx = (idx < 0) ? model.getListModel().size() - 1 : Math.min(idx + 1, model.getListModel().size() - 1);
        stepList.setSelectedIndex(newIdx);
        openEditor();  // immediately open editor for the new step
    }

    private void removeSelectedStep() {
        int idx = stepList.getSelectedIndex();
        if (idx < 0) return;
        String name = model.getListModel().get(idx).getName();
        if (name == null || name.isBlank()) name = model.getListModel().get(idx).getType();
        int confirm = JOptionPane.showConfirmDialog(this,
                "Remove step '" + name + "'?", "Confirm Remove",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        model.removeStep(idx);
        showEmptyDetail();
        updateButtonStates();
    }

    private void moveStep(int delta) {
        int idx = stepList.getSelectedIndex();
        if (idx < 0) return;
        if (delta < 0) model.moveUp(idx);
        else           model.moveDown(idx);
        stepList.setSelectedIndex(idx + delta);
    }

    private void openEditor() {
        int idx = stepList.getSelectedIndex();
        if (idx < 0) return;

        // Commit previous editor first
        if (currentEditor != null && currentEditorIndex >= 0 && currentEditorIndex < model.getListModel().size()) {
            commitCurrentEditor();
        }

        StepData step = model.getListModel().get(idx);
        StepEditorPanel<?> panel = createEditorFor(step);
        populateEditor(panel, step);

        detailHost.removeAll();

        // Wrap in a save-bar
        JButton saveStepBtn = new JButton("Apply Changes");
        saveStepBtn.addActionListener(e -> commitCurrentEditor());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(saveStepBtn);

        detailHost.add(panel, BorderLayout.CENTER);
        detailHost.add(south, BorderLayout.SOUTH);
        detailHost.revalidate();
        detailHost.repaint();

        currentEditor = panel;
        currentEditorIndex = idx;
    }

    @SuppressWarnings("unchecked")
    private void commitCurrentEditor() {
        if (currentEditor == null || currentEditorIndex < 0) return;
        if (currentEditorIndex >= model.getListModel().size()) return;
        StepData step = model.getListModel().get(currentEditorIndex);
        currentEditor.applyTo(step);
        model.updateStep(currentEditorIndex, step);
        stepList.repaint();  // force renderer refresh
    }

    private void runWorkflow() {
        applyHeaderFields();
        if (currentEditor != null) commitCurrentEditor();

        InputParamsDialog dlg = new InputParamsDialog(
                this,
                model.getParams(),
                model.getParamDefaults(),
                lastRunParams);
        dlg.setVisible(true);
        Map<String, Object> params = dlg.getParams();
        if (params == null) return;  // cancelled
        // Persist last-used values for the next invocation
        lastRunParams = dlg.getLastUsed();

        executionPanel.showRunning();
        runBtn.setEnabled(false);

        WorkflowData snapshot = model.getWorkflow();

        bgExecutor.submit(() -> {
            try {
                WorkflowExecutorService executor = new WorkflowExecutorService(contextFactory);
                WorkflowResult result = executor.executeWorkflow(snapshot, params);
                SwingUtilities.invokeLater(() -> {
                    executionPanel.showResult(result);
                    runBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    executionPanel.showError(ex.getMessage() != null ? ex.getMessage() : ex.toString());
                    runBtn.setEnabled(true);
                });
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Re-generates the PUML source from the current model and displays it in the PUML tab. */
    private void refreshPumlTab() {
        try {
            // NOTE: do NOT call applyHeaderFields() or commitCurrentEditor() here.
            // Both mutate the model → markDirty() → fireChange() → re-entrant call → StackOverflow.
            // Callers that need to flush pending edits must do so before invoking this method.
            String puml = pumlSerializer.serialize(model.getWorkflow());
            pumlPane.setText(puml);
            pumlPane.setCaretPosition(0);
        } catch (Exception ex) {
            pumlPane.setText("' Error generating PUML:\n' " + ex.getMessage());
        }
    }

    /** Sends the current PUML source to the diagram panel for rendering. */
    private void refreshDiagramTab() {
        try {
            // NOTE: same constraint as refreshPumlTab() — do not mutate the model here.
            String puml = pumlSerializer.serialize(model.getWorkflow());
            pumlDiagramPanel.render(puml);
        } catch (Exception ex) {
            // diagram panel will show its own error state; nothing further to do
        }
    }

    private void onSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        updateButtonStates();
    }

    private void updateButtonStates() {
        int idx  = stepList.getSelectedIndex();
        int size = model.getListModel().size();
        boolean hasSelection = idx >= 0;
        removeBtn.setEnabled(hasSelection);
        upBtn.setEnabled(idx > 0);
        downBtn.setEnabled(hasSelection && idx < size - 1);
        editBtn.setEnabled(hasSelection);
    }

    private void applyHeaderFields() {
        model.setName(wfNameField.getText().trim());
        model.setResultFrom(wfResultFromField.getText().trim());

        // Collect params table → model
        List<String> names    = new ArrayList<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            String name = str(paramsTableModel.getValueAt(i, 0));
            String def  = str(paramsTableModel.getValueAt(i, 1));
            if (!name.isBlank()) {
                names.add(name);
                if (!def.isBlank()) defaults.put(name, def);
            }
        }
        model.setParams(names);
        model.setParamDefaults(defaults);
    }

    private void populateHeaderFields() {
        wfNameField.setText(model.getName() != null ? model.getName() : "");
        wfResultFromField.setText(model.getResultFrom() != null ? model.getResultFrom() : "");

        // Populate params table from model
        paramsTableModel.setRowCount(0);
        List<String> params = model.getParams();
        Map<String, String> defaults = model.getParamDefaults();
        if (params != null) {
            for (String name : params) {
                String def = defaults != null ? defaults.getOrDefault(name, "") : "";
                paramsTableModel.addRow(new Object[]{name, def});
            }
        }
    }

    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }

    private void syncTitle() {
        String name = model.getName() != null ? model.getName() : "untitled";
        String file = currentFile != null ? " — " + currentFile.getName() : "";
        String dirty = model.isDirty() ? " *" : "";
        setTitle("Workflow Designer — " + name + file + dirty);
    }

    private boolean confirmDiscard() {
        if (!model.isDirty()) return true;
        int r = JOptionPane.showConfirmDialog(this,
                "There are unsaved changes. Discard them?",
                "Unsaved Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    private void confirmAndClose() {
        if (!confirmDiscard()) return;
        bgExecutor.shutdownNow();
        dispose();
        System.exit(0);
    }

    private void showError(String msg, Exception ex) {
        JOptionPane.showMessageDialog(this,
                msg + ":\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }

    /** File chooser that accepts both JSON and PUML workflow files. */
    private static JFileChooser workflowFileChooser() {
        JFileChooser fc = new JFileChooser();
        fc.addChoosableFileFilter(new FileNameExtensionFilter("JSON workflow (*.json)", "json"));
        fc.addChoosableFileFilter(new FileNameExtensionFilter("PlantUML workflow (*.puml)", "puml"));
        fc.setFileFilter(new FileNameExtensionFilter("Workflow files (*.json, *.puml)", "json", "puml"));
        fc.setAcceptAllFileFilterUsed(false);
        return fc;
    }

    private static JFileChooser pumlFileChooser() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("PlantUML files (*.puml)", "puml"));
        fc.setAcceptAllFileFilterUsed(false);
        return fc;
    }

    private static boolean isPuml(File f) {
        return f != null && f.getName().toLowerCase().endsWith(".puml");
    }

    private static File ensureJsonExtension(File f) {
        return f.getName().endsWith(".json") ? f : new File(f.getPath() + ".json");
    }

    private static File ensurePumlExtension(File f) {
        return f.getName().endsWith(".puml") ? f : new File(f.getPath() + ".puml");
    }

    private static JButton toolButton(String text, String tooltip) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        return b;
    }

    // -----------------------------------------------------------------------
    // Editor factory
    // -----------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private StepEditorPanel createEditorFor(StepData step) {
        return editorPanelFactory.createFor(step);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void populateEditor(StepEditorPanel panel, StepData step) {
        panel.populate(step);
    }

    // -----------------------------------------------------------------------
    // Key bindings
    // -----------------------------------------------------------------------

    private void bindKeys() {
        JRootPane root = getRootPane();
        InputMap  im  = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am  = root.getActionMap();

        // Ctrl+N/O/S and F5 are safe everywhere — they don't conflict with text editing.
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "new");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "open");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "run");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "add");

        // Delete and Enter must NOT fire when a text input has focus — otherwise
        // they eat keystrokes inside JTextField / JTextArea / JTable cell editors
        // and break copy/paste and normal text editing.
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit");

        am.put("new",    action(e -> newWorkflow()));
        am.put("open",   action(e -> openFile()));
        am.put("save",   action(e -> saveFile(false)));
        am.put("run",    action(e -> runWorkflow()));
        am.put("add",    action(e -> addStep()));
        am.put("remove", action(e -> { if (!textInputFocused()) removeSelectedStep(); }));
        am.put("edit",   action(e -> { if (!textInputFocused()) openEditor(); }));
    }

    /**
     * Returns {@code true} when a text-editing component currently has keyboard focus.
     * Used to suppress global Delete/Enter shortcuts so they don't interfere with
     * typing, copy/paste, or table cell editing.
     */
    private static boolean textInputFocused() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focused instanceof JTextComponent || focused instanceof JComboBox;
    }

    private static Action action(ActionListener al) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { al.actionPerformed(e); }
        };
    }

    // -----------------------------------------------------------------------
    // Session restore
    // -----------------------------------------------------------------------

    /**
     * On startup, silently reopen the last saved/opened file.
     * Uses {@link Preferences} — no UI shown if the file is missing or corrupt.
     */
    private void restoreLastFile() {
        String path = PREFS.get(PREF_LAST_FILE, null);
        if (path == null) return;
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return;
        try {
            WorkflowData wf = isPuml(f) ? pumlParser.parse(f.toPath()) : serializer.read(f.toPath());
            model.load(wf);
            currentFile = f;
            populateHeaderFields();
            syncTitle();
        } catch (Exception ignored) {
            // Stale or corrupt file — just start with an empty workflow
        }
    }
}
