package ai.mindconnect.agent.runtime.capabilities.permission;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Lets the agent put a question to the user; the turn comes back interrupted and the answer is the result. */
final class AskUserTool implements Tool {

    static final String NAME = "ask_user";
    private static final String QUESTION = "question";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "Asks the user a question and returns the answer. Use it when you cannot go on "
                        + "without something only the user knows or decides.",
                Map.of("type", "object",
                        "properties", Map.of(QUESTION, Map.of("type", "string",
                                "description", "The question, as the user should read it")),
                        "required", List.of(QUESTION)));
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        if (!(call.arguments().get(QUESTION) instanceof String question) || question.isBlank()) {
            return ToolOutput.failed("Error: " + NAME + " needs a question");
        }
        return ToolOutput.interrupt(Interruption.USER_INPUT, Map.of(QUESTION, question));
    }
}
