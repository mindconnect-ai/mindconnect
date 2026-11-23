package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Map;

/**
 * Work that must not hold up a turn: summarising a long session, finding a
 * title. A capability hands it over here and contributes a
 * {@link BackgroundWorker} of the same name; the engine runs it, durably
 * where the engine is durable.
 */
public interface BackgroundWork {

    /**
     * @param workerName the {@link BackgroundWorker#name()} that does the work
     * @param payload    what the worker needs, in values that survive being
     *                   stored: strings, numbers, booleans, lists and maps
     */
    void submit(String workerName, Map<String, Object> payload);
}
