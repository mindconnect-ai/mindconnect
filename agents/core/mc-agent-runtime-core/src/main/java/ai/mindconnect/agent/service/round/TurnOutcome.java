package ai.mindconnect.agent.service.round;

import java.util.Set;

/**
 * How a whole turn ended — the sum of its rounds. {@link RoundOutcome}s are
 * steps, this is the result. Runtime-owned; the protocol's ResponseStatus is
 * mapped at the API edge.
 *
 * <p>An outcome is not necessarily the end: {@link #waitsForTools()} means the
 * turn continues as soon as a tool finishes — the caller (a task worker)
 * suspends on those calls instead of holding a thread.
 *
 * @param rounds     how many model rounds were turned; log and task-manager food
 * @param waitingFor the callIds being waited for — only for {@link Status#WAITING_FOR_TOOLS}
 * @param text       the final answer as persisted (reviewers applied) — only
 *                   for {@link Status#COMPLETED}, empty otherwise
 */
public record TurnOutcome(Status status, IncompleteReason incompleteReason,
                          Usage usage, int rounds, Set<String> waitingFor, String text) {

    public enum Status { COMPLETED, INCOMPLETE, CANCELLED, WAITING_FOR_TOOLS }

    public enum IncompleteReason { MAX_ROUNDS, MAX_OUTPUT_TOKENS }

    public static TurnOutcome completed(Usage usage, int rounds, String text) {
        return new TurnOutcome(Status.COMPLETED, null, usage, rounds, Set.of(), text);
    }

    public static TurnOutcome incomplete(IncompleteReason reason, Usage usage, int rounds) {
        return new TurnOutcome(Status.INCOMPLETE, reason, usage, rounds, Set.of(), "");
    }

    public static TurnOutcome cancelled(Usage usage, int rounds) {
        return new TurnOutcome(Status.CANCELLED, null, usage, rounds, Set.of(), "");
    }

    /** Not the end — the turn resumes when these calls are done. */
    public static TurnOutcome waitingForTools(Set<String> callIds, Usage usage, int rounds) {
        return new TurnOutcome(Status.WAITING_FOR_TOOLS, null, usage, rounds, Set.copyOf(callIds), "");
    }

    public boolean waitsForTools() {
        return status == Status.WAITING_FOR_TOOLS;
    }

    @Override
    public String toString() {
        if (waitsForTools()) {
            return "WAITING_FOR_TOOLS on " + waitingFor + " after " + rounds + " round(s)";
        }
        return incompleteReason == null
                ? status.name() + " after " + rounds + " round(s)"
                : status.name() + ":" + incompleteReason.name() + " after " + rounds + " round(s)";
    }
}
