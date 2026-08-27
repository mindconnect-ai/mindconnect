package ai.mindconnect.agent.protocol.api;

/** Handle to an active event subscription. Closing detaches the consumer. */
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
