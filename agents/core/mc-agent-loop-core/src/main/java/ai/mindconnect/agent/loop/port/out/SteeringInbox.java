package ai.mindconnect.agent.loop.port.out;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * What the user said while the turn was running — messages meant to steer
 * the work in progress instead of waiting for its end. An instance is bound
 * to one turn.
 *
 * <p>The loop asks here at the two moments a user message can enter a turn
 * without breaking it: before the model is asked, once no tool call is open,
 * and after the model answered, where a pending message keeps the turn going
 * instead of ending it. Never between a tool call and its result — a history
 * with a user message inside an open call pair is one no provider accepts.
 *
 * <p>The inbox lives outside the loop because the turn does: a turn that
 * waits for a tool returns and is resumed later, possibly elsewhere. What is
 * still here when the turn has ended is the caller's — it is the input of
 * the next turn.
 */
@FunctionalInterface
public interface SteeringInbox {

    /**
     * Hands over the messages to deliver now and forgets them: the loop
     * appends each to the {@link TurnJournal} before the next round. All
     * pending messages or one at a time is this implementation's choice.
     * Each is a {@link TurnMessage.Type#USER} message; an empty list when
     * nobody said anything.
     */
    List<TurnMessage> drain();
}
