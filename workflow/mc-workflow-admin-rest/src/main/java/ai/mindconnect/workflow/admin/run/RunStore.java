package ai.mindconnect.workflow.admin.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the most recent runs so the run view can open a step's log, scope or
 * instance JSON after the response has been rendered. Without it those buttons
 * would have nothing to fetch: a run is executed inside one request and the
 * trace would be gone by the time the user clicks.
 *
 * <p><b>In memory, bounded, and lost on restart</b> — on purpose. It holds only
 * the {@link WorkflowRunService.RunReport} value objects, never a live
 * {@code StepInstance} (those would pin the workflow context and its script
 * engines). Durable run history is a separate job; this is the smallest thing
 * that makes the run view interactive, and it is the shape a persistent run
 * store would take.
 */
public class RunStore {

    /** Runs kept before the oldest is dropped. Roughly a session's worth. */
    private static final int MAX_RUNS = 50;

    private final Map<String, WorkflowRunService.RunReport> runs =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, WorkflowRunService.RunReport> eldest) {
                    return size() > MAX_RUNS;
                }
            };

    /** Stores {@code report} and returns the id the run view addresses it by. */
    public synchronized String put(WorkflowRunService.RunReport report) {
        String runId = UUID.randomUUID().toString();
        runs.put(runId, report);
        return runId;
    }

    /** Empty when the run has expired out of the store (or never existed). */
    public synchronized Optional<WorkflowRunService.RunReport> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }
}
