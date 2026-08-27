package ai.mindconnect.channel;

/**
 * Ops-level observation of the REGISTRY (not of event streams — subscribers
 * are that): which channels exist, when they materialize, when eviction
 * reclaims them. Feeds metrics and the task-manager's leak visibility.
 * Default no-ops; exceptions are swallowed.
 */
public interface ChannelLifecycleListener {

    default void onMaterialized(String channelId) { }

    default void onEvicted(String channelId) { }
}
