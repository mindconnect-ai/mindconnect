package ai.mindconnect.workflow.execution;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Runtime metadata captured during the execution of a single step. */
@Data
public class StepExecutionInfo {

    private int positionInStepContainer = 0;
    private long startTime;
    private long endTime;
    private String errorMessage;
    private ExecutionState state = ExecutionState.INITIAL;
    private List<LogEntry> logs = new ArrayList<>();

    public long getDurationInMs() {
        return endTime - startTime;
    }

    public void addLog(String level, String message) {
        this.logs.add(new LogEntry(level, message));
    }

    public void addDebug(String message) {
        addLog("DEBUG", message);
    }
}
