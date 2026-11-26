package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
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
     * The tool hands the call to other turns: the engine runs the requests
     * side by side as children of the calling turn, and their answers are
     * the call's result — the one answer as it is, several together, each
     * under the name of its agent. This is what makes a sub-agent an
     * ordinary tool: it needs no way to start turns, only to say which ones
     * it wants.
     */
    record Delegated(List<TurnRequest> requests) implements ToolOutput {

        public Delegated {
            requests = List.copyOf(requests);
        }
    }

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
        return new Delegated(List.of(request));
    }

    static ToolOutput delegate(List<TurnRequest> requests) {
        return new Delegated(requests);
    }
}
