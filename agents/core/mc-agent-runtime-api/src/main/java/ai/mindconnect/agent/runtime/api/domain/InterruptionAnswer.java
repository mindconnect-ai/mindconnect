package ai.mindconnect.agent.runtime.api.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What someone answered to an {@link Interruption}, handed back with the
 * turn when the caller resumes it. The answer is data whose shape belongs
 * to the interruption's kind; the factories give the shapes the runtime's
 * own kinds use.
 *
 * @param interruptionId which interruption this answers
 */
public record InterruptionAnswer(String interruptionId, Map<String, Object> answer) {

    /** In an answer to a tool approval: whether the call may run. */
    public static final String APPROVED = "approved";
    /** In an answer to a tool approval: do not ask about this tool again. */
    public static final String REMEMBER = "remember";
    /** What was said — a rejection's note, the user's reply, a colleague's answer. */
    public static final String TEXT = "text";

    public InterruptionAnswer {
        answer = Map.copyOf(answer);
    }

    /** The call may run, this once. */
    public static InterruptionAnswer approve(String interruptionId) {
        return new InterruptionAnswer(interruptionId, Map.of(APPROVED, true));
    }

    /**
     * The call may run, and the tool is not to be asked about again — how
     * long and where that is remembered is the permission policy's business.
     */
    public static InterruptionAnswer approveAlways(String interruptionId) {
        return new InterruptionAnswer(interruptionId, Map.of(APPROVED, true, REMEMBER, true));
    }

    /** The call may not run; {@code note} is what the model reads instead of a result. */
    public static InterruptionAnswer reject(String interruptionId, String note) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(APPROVED, false);
        answer.put(TEXT, note);
        return new InterruptionAnswer(interruptionId, answer);
    }

    /** A reply in words — to a question, a hand-off — which becomes the call's result. */
    public static InterruptionAnswer text(String interruptionId, String text) {
        return new InterruptionAnswer(interruptionId, Map.of(TEXT, text));
    }
}
