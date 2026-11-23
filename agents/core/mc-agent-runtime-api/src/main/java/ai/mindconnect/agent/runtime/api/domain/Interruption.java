package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;

/**
 * A tool call that cannot go on without someone outside the runtime. The
 * turn stops and comes back with it; it goes on when the caller resumes the
 * turn with an answer. Nothing waits in a thread meanwhile: between the
 * question and the answer may lie a minute or a restart.
 *
 * <p>One mechanism for everything a turn has to ask: whether a tool may
 * run, a question to the user, a hand-off to a human colleague, a login. The
 * {@link #kind} tells a caller how to show it and what an answer looks
 * like; what the runtime does with the answer follows from the kind too —
 * for {@link #TOOL_APPROVAL} it decides whether the call runs, for every
 * other kind the answer IS the call's result.
 *
 * @param turnId  the turn whose call waits — a sub-agent's turn when the
 *                call is one of its
 * @param kind    what is asked; {@link #TOOL_APPROVAL} and
 *                {@link #USER_INPUT} are the runtime's own, a tool may
 *                bring others
 * @param call    the call that waits
 * @param details what whoever answers needs to know — the reason a tool
 *                needs approval, the question to the user
 */
public record Interruption(String id, TurnId turnId, String kind, ToolCall call, Map<String, Object> details) {

    /** A tool may not run unasked: the answer says whether it runs. */
    public static final String TOOL_APPROVAL = "tool_approval";

    /** The agent asks the user something: the answer is what the user said. */
    public static final String USER_INPUT = "user_input";

    public Interruption {
        details = Map.copyOf(details);
    }
}
