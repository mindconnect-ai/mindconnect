package ai.mindconnect.agent.loop.domain;

import java.util.List;

/**
 * How far a turn has come: the model rounds it has turned and what they
 * cost. Nothing here is stored on its own — every message a model call
 * produced carries that call's usage ({@link TurnMessage#usage()}), and this
 * type counts them. That is what lets a turn that waited for a tool go on
 * without anyone having kept a counter: a counter kept next to the messages
 * could disagree with them after a crash, a count taken from them cannot.
 *
 * <p>Feed it {@link ToolCalls#episode(List)}, as the tool-call fold: the
 * rounds of earlier turns are not this turn's.
 */
public record TurnProgress(int modelRounds, Usage usage) {

    public static TurnProgress of(List<TurnMessage> episode) {
        int modelRounds = 0;
        Usage usage = Usage.ZERO;
        for (TurnMessage message : episode) {
            if (message.usage().isPresent()) {
                modelRounds++;
                usage = usage.plus(message.usage().get());
            }
        }
        return new TurnProgress(modelRounds, usage);
    }
}
