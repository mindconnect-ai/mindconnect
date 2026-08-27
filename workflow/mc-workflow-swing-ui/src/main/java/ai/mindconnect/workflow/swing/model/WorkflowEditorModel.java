package ai.mindconnect.workflow.swing.model;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;

import javax.swing.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observable model that backs the workflow designer.
 *
 * <p>Wraps a {@link WorkflowData} and exposes its step list as a
 * {@link DefaultListModel} so Swing components stay in sync automatically.
 *
 * <p>All mutating methods fire change events on the list model; callers
 * can also attach a change listener via {@link #addChangeListener} to be notified of workflow-level
 * changes (name, params, dirty flag).
 */
public class WorkflowEditorModel {

    private WorkflowData workflow;
    private final DefaultListModel<StepData> listModel = new DefaultListModel<>();
    private boolean dirty = false;

    private final List<Runnable> changeListeners = new ArrayList<>();
    /** Prevents re-entrant fireChange() calls from causing infinite recursion. */
    private boolean firingChange = false;

    public WorkflowEditorModel() {
        this.workflow = new WorkflowData();
        this.workflow.setName("untitled");
    }

    // -----------------------------------------------------------------------
    // Workflow metadata
    // -----------------------------------------------------------------------

    public WorkflowData getWorkflow() { return workflow; }

    public String getName() { return workflow.getName(); }

    public void setName(String name) {
        workflow.setName(name);
        markDirty();
    }

    public String getResultFrom() { return workflow.getResultFrom(); }

    public void setResultFrom(String resultFrom) {
        workflow.setResultFrom(resultFrom);
        markDirty();
    }

    public List<String> getParams() {
        return workflow.paramNames();
    }

    public void setParams(List<String> params) {
        workflow.declareParams(params != null ? params.toArray(new String[0]) : new String[0]);
        markDirty();
    }

    public Map<String, String> getParamDefaults() {
        Map<String, String> defaults = workflow.getParamDefaults();
        return defaults != null ? defaults : new LinkedHashMap<>();
    }

    public void setParamDefaults(Map<String, String> defaults) {
        workflow.setParamDefaults(defaults != null ? new LinkedHashMap<>(defaults) : new LinkedHashMap<>());
        markDirty();
    }

    // -----------------------------------------------------------------------
    // Step list operations
    // -----------------------------------------------------------------------

    public DefaultListModel<StepData> getListModel() { return listModel; }

    /** Replaces the entire workflow (e.g. after loading from JSON). */
    public void load(WorkflowData wf) {
        this.workflow = wf;
        listModel.clear();
        if (wf.getSteps() != null) {
            for (StepData step : wf.getSteps()) listModel.addElement(step);
        }
        dirty = false;
        fireChange();
    }

    /** Appends a new step at the end. */
    public void addStep(StepData step) {
        listModel.addElement(step);
        syncStepsToWorkflow();
        markDirty();
    }

    /** Inserts a new step after the currently selected index (or at end if -1). */
    public void insertStepAfter(int index, StepData step) {
        int insertAt = (index < 0) ? listModel.size() : index + 1;
        listModel.insertElementAt(step, Math.min(insertAt, listModel.size()));
        syncStepsToWorkflow();
        markDirty();
    }

    /** Removes the step at the given index. */
    public void removeStep(int index) {
        if (index >= 0 && index < listModel.size()) {
            listModel.remove(index);
            syncStepsToWorkflow();
            markDirty();
        }
    }

    /** Moves the step at {@code index} one position up. */
    public void moveUp(int index) {
        if (index > 0) {
            StepData a = listModel.get(index - 1);
            StepData b = listModel.get(index);
            listModel.set(index - 1, b);
            listModel.set(index, a);
            syncStepsToWorkflow();
            markDirty();
        }
    }

    /** Moves the step at {@code index} one position down. */
    public void moveDown(int index) {
        if (index >= 0 && index < listModel.size() - 1) {
            StepData a = listModel.get(index);
            StepData b = listModel.get(index + 1);
            listModel.set(index, b);
            listModel.set(index + 1, a);
            syncStepsToWorkflow();
            markDirty();
        }
    }

    /** Replaces the step at {@code index} with the updated version. */
    public void updateStep(int index, StepData step) {
        if (index >= 0 && index < listModel.size()) {
            listModel.set(index, step);
            syncStepsToWorkflow();
            markDirty();
        }
    }

    // -----------------------------------------------------------------------
    // Dirty flag
    // -----------------------------------------------------------------------

    public boolean isDirty() { return dirty; }

    public void clearDirty() {
        dirty = false;
        fireChange();
    }

    // -----------------------------------------------------------------------
    // Change listeners
    // -----------------------------------------------------------------------

    public void addChangeListener(Runnable listener) { changeListeners.add(listener); }

    private void markDirty() {
        dirty = true;
        fireChange();
    }

    private void fireChange() {
        if (firingChange) return;  // prevent re-entrant calls
        firingChange = true;
        try {
            changeListeners.forEach(Runnable::run);
        } finally {
            firingChange = false;
        }
    }

    // -----------------------------------------------------------------------
    // Internal sync
    // -----------------------------------------------------------------------

    private void syncStepsToWorkflow() {
        List<StepData> steps = new ArrayList<>(listModel.size());
        for (int i = 0; i < listModel.size(); i++) steps.add(listModel.get(i));
        workflow.setSteps(steps);
    }
}
