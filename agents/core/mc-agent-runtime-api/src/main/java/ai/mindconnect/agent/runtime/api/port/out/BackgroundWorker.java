package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Map;

/** Does one kind of {@link BackgroundWork}. Contributed, found by name. */
public interface BackgroundWorker {

    String name();

    /** May run more than once for one submission: a worker that died is run again. */
    void run(Map<String, Object> payload);
}
