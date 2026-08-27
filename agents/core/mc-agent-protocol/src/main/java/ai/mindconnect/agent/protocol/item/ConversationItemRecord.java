package ai.mindconnect.agent.protocol.item;

/**
 * One entry of a conversation's log: what happened ({@link #item}) plus where
 * it sits ({@link #id}, {@link #seq}). Identity is assigned on append by
 * whoever owns the log — the conversation store durably, a transcript in
 * memory — which is why it travels beside the item rather than inside it:
 * two equal items are equal values, two appends are two entries.
 *
 * <p>{@code seq} is the DOMAIN sequence: strictly increasing per conversation
 * and the cursor clients reconnect on. UI clients key their nodes on
 * {@code id}, so a reload mid-run rebuilds the same nodes.
 */
public record ConversationItemRecord(String id, long seq, ConversationItem item) { }
