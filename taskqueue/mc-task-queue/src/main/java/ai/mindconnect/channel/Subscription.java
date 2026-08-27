package ai.mindconnect.channel;

/** Handle to an active subscription; closing detaches the consumer. */
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
