package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * How much of the model's context a text takes. An interface here because
 * memory, tool results and guards all need the same answer, and how exact it
 * is — an estimate, a real tokenizer — is a choice of the assembly.
 */
public interface TokenCounter {

    int count(String text);

    default int count(List<TurnMessage> messages) {
        return messages.stream().mapToInt(message -> count(message.text())).sum();
    }
}
