package ai.mindconnect.workflow.persistence.memory;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link WorkflowDataRepository} that keeps everything in a map.
 *
 * <p>For tests, and for a runner that ships a fixed set of workflows it never
 * writes back to disk. Nothing here survives a restart — that is the whole
 * difference from the file-backed one, and the reason both exist behind one port.
 */
public class InMemoryWorkflowDataRepository implements WorkflowDataRepository {

    private final Map<String, WorkflowData> workflows = new ConcurrentHashMap<>();

    @Override
    public List<String> listIds() {
        List<String> ids = new ArrayList<>(workflows.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    @Override
    public Optional<WorkflowData> findById(String id) {
        return Optional.ofNullable(workflows.get(id));
    }

    @Override
    public void save(String id, WorkflowData workflow) {
        workflows.put(id, workflow);
    }

    @Override
    public boolean delete(String id) {
        return workflows.remove(id) != null;
    }
}
