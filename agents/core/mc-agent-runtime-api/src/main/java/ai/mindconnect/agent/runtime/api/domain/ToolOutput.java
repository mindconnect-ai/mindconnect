package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;

/** What became of running a tool. */
public sealed interface ToolOutput {

    /**
     * The tool gave something back. A failure is an output too: the model
     * reads it and reacts, the turn goes on.
     */
    record Completed(String text, boolean failed) implements ToolOutput { }

    /**
     * The tool cannot answer by itself — it has a question for the user,
     * hands off to a human. The turn comes back with an {@link Interruption}
     * of this kind, and the answer the caller resumes with is the call's
     * result.
     */
    record Interrupted(String kind, Map<String, Object> details) implements ToolOutput {

        public Interrupted {
            details = Map.copyOf(details);
        }
    }

    /**
     * The tool hands the call to another turn: the engine runs the request as
     * a child of the calling turn and that turn's answer is the call's
     * result. This is what makes a sub-agent an ordinary tool — it needs no
     * way to start turns, only to say which one it wants.
     */
    record Delegated(TurnRequest request) implements ToolOutput { }

    static ToolOutput ok(String text) {
        return new Completed(text, false);
    }

    static ToolOutput failed(String error) {
        return new Completed(error, true);
    }

    static ToolOutput interrupt(String kind, Map<String, Object> details) {
        return new Interrupted(kind, details);
    }

    static ToolOutput delegate(TurnRequest request) {
        return new Delegated(request);
    }
}
