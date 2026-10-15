package ai.mindconnect.agent.protocol.api;


/**
 * Where and how to attach to a response's event stream.
 *
 * @param afterSeq        deliver events with {@code seq > afterSeq}; {@code 0}
 *                        replays from the start (stored items re-rendered as
 *                        {@code OutputItemAdded/Done}, no deltas)
 * @param includeChildren also deliver events of child responses (sub-agents),
 *                        multiplexed into this subscription. Events keep their
 *                        own response id and per-response seq — aggregation is
 *                        a subscription option, never an event structure.
 */
public record SubscribeRequest(String responseId, long afterSeq, boolean includeChildren) {

    public static SubscribeRequest live(String responseId) {
        return new SubscribeRequest(responseId, Long.MAX_VALUE, false);
    }

    public static SubscribeRequest replay(String responseId) {
        return new SubscribeRequest(responseId, 0, false);
    }

    public SubscribeRequest withChildren() {
        return new SubscribeRequest(responseId, afterSeq, true);
    }
}
