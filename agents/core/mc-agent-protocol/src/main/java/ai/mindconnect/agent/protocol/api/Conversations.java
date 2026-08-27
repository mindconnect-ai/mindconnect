package ai.mindconnect.agent.protocol.api;

import ai.mindconnect.agent.protocol.Conversation;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import java.util.List;
import java.util.Optional;

/**
 * The state side of the protocol: durable item logs, independent of any
 * execution. Sessions (agent binding, memory state) sit above conversations
 * and are intentionally not part of this surface.
 */
public interface Conversations {

    Conversation create(String namespace);

    Optional<Conversation> get(String conversationId);

    /**
     * Appends an item outside of a run (seeding context, importing history).
     * Items produced by a run are appended by the run itself.
     */
    ConversationItemRecord append(String conversationId, ConversationItem item);

    /**
     * Items in append order with {@code seq > afterSeq}. What a turn's memory
     * hid or compacted is not expressed here — that belongs to the entry, not
     * to the item.
     */
    List<ConversationItemRecord> items(String conversationId, long afterSeq, int limit);
}
