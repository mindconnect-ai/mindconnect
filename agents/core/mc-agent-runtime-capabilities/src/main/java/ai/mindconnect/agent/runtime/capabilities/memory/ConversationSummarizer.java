package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;

/**
 * Asks the model for a summary of the part of a conversation that no longer
 * fits. The conversation goes out as one text, not as its messages: a tool
 * call without the tools it names is something no provider accepts.
 */
final class ConversationSummarizer {

    private static final String INSTRUCTIONS = """
            Summarise the conversation below so that it can be continued by someone who has not read it. \
            Keep what the user wants, what was decided, what was found out and what is still open. \
            Keep names, numbers and file paths exactly. Answer with the summary only.""";

    private final Supplier<ModelGateway> model;

    ConversationSummarizer(Supplier<ModelGateway> model) {
        this.model = model;
    }

    /**
     * @param turnId   the turn this is done for: a cancel of the turn ends
     *                 the summarising too
     * @param settings the model that summarises — the agent's own
     * @param earlier  the summary so far, which the new one has to contain
     */
    String summarize(TurnId turnId, ModelSettings settings, Optional<ConversationSummary> earlier,
                     List<TurnMessage> messages) {
        String conversation = earlier.map(summary -> "Summary of what came before:\n" + summary.text() + "\n\n")
                .orElse("")
                + messages.stream()
                        .filter(message -> !message.text().isBlank())
                        .map(message -> message.type().name().toLowerCase() + ": " + message.text())
                        .collect(Collectors.joining("\n\n"));
        return model.get().ask(new ModelRequest(turnId, settings, INSTRUCTIONS,
                        List.of(TurnMessage.user(List.of(new ContentPart.Text(conversation)))), List.of()),
                        event -> { })
                .messages().stream().map(TurnMessage::text).collect(Collectors.joining("\n")).strip();
    }
}
