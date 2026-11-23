package ai.mindconnect.agent.runtime.capabilities.trace;

/**
 * Where traces go. A port of its own rather than the generic store: traces
 * are asked for by time, user and agent, which a store by key cannot answer.
 */
public interface TraceSink {

    void record(TraceRecord trace);
}
