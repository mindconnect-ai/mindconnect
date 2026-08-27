package ai.mindconnect.agent.service.round;

import java.util.List;

/**
 * One round, as it happened. {@code added} is the increment, not the whole
 * history: exactly what gets persisted and streamed. The caller appends it to
 * its list and turns again — or does not, and {@code outcome} says which.
 *
 * @param added      what this round produced, in order; empty when only waiting
 * @param outcome    what the caller should do next
 * @param usage      token usage of this round; {@link Usage#ZERO} in tool rounds
 * @param waitingFor the callIds whose result is still outstanding — only
 *                   filled for {@link Outcome#WAITING_FOR_TOOLS}, so the
 *                   caller can wait for them instead of polling
 */
public record RoundOutcome(List<TurnMessage> added, Outcome outcome, Usage usage,
                           List<String> waitingFor) {

    public RoundOutcome(List<TurnMessage> added, Outcome outcome, Usage usage) {
        this(added, outcome, usage, List.of());
    }

    public enum Outcome {

        /** The model requested tools — turn again. */
        CALLS_REQUESTED,

        /** The model answered without tools — the turn is done. */
        ANSWERED,

        /**
         * The model was cut off at the output limit. Turning on would be
         * wrong: a half-emitted tool call is incomplete.
         */
        TRUNCATED,

        /** Dispatched, collected or refused something — turn again. */
        TOOLS_ADVANCED,

        /**
         * Nothing to do, a tool is still running — which one, says
         * {@link RoundOutcome#waitingFor()}. Wait for those calls, then turn
         * again; turning immediately would be a busy loop.
         */
        WAITING_FOR_TOOLS;

        /** Whether the caller should turn another round right away. */
        public boolean continues() {
            return this == CALLS_REQUESTED || this == TOOLS_ADVANCED;
        }
    }
}
