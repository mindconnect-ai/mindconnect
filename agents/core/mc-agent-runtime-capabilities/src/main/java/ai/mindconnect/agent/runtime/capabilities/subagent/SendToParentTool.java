package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Lets a child session tell the session it was started from something, without ending its own work. */
final class SendToParentTool implements Tool {

    static final String NAME = "send_to_parent";
    private static final String MESSAGE = "message";
    static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Sends a message to the session this one was started from. Its agent reads the message "
                    + "and reacts to it. Use it to report what you found or to raise something the "
                    + "other session has to know; your own work goes on.",
            Map.of("type", "object",
                    "properties", Map.of(MESSAGE, Map.of("type", "string",
                            "description", "What to tell the other session, standing on its own: "
                                    + "it has not seen what you did.")),
                    "required", List.of(MESSAGE)));

    private final Supplier<TurnService> turns;

    SendToParentTool(Supplier<TurnService> turns) {
        this.turns = turns;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        if (session.parentId() == null) {
            return ToolOutput.failed("Error: this session was not started from another one");
        }
        if (!(call.arguments().get(MESSAGE) instanceof String message) || message.isBlank()) {
            return ToolOutput.failed("Error: " + NAME + " needs a message");
        }
        try {
            // Marked with where it comes from: the parent's agent must be
            // able to tell it from what its user says.
            TurnId turnId = turns.get().submit(new TurnRequest(session.parentId(),
                    List.of(new ContentPart.Text("[Message from sub-session " + session.id().value() + "]\n"
                            + message)),
                    Map.of()));
            return ToolOutput.ok("The message was delivered; the other session answers it in turn "
                    + turnId.value() + ".");
        } catch (IllegalArgumentException e) {
            return ToolOutput.failed("Error: the message could not be delivered — " + e.getMessage());
        }
    }
}
