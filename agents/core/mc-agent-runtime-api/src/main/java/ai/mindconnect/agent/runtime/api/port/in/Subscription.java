package ai.mindconnect.agent.runtime.api.port.in;

/** A listener's place on a turn's events; closing it ends the listening, not the turn. */
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
