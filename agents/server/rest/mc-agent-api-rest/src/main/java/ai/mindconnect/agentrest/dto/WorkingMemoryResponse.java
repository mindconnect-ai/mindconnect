package ai.mindconnect.agentrest.dto;

import java.util.ArrayList;
import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;

/**
 * A session's working memory as this API has always answered it: the system
 * prompt and the messages the model is sent, with their tokens. The fields
 * and their names are the wire contract clients read; what they hold comes
 * from the runtime's {@link WorkingMemory}.
 *
 * @param messages only the messages that are sent; {@code sequenceNum} is a
 *                 message's place in the session, counted from one
 */
public record WorkingMemoryResponse(
        String systemPrompt,
        int systemPromptTokens,
        List<Message> messages,
        int totalTokens,
        Integer contextWindowTokens
) {

    /**
     * @param type              {@code CHAT} for what a user or the agent
     *                          said, else the kind of tool message
     * @param compressedContent the note the model reads in the message's
     *                          place; null for one sent as it was said
     */
    public record Message(String type, String role, int sequenceNum, int tokens, String content,
                          boolean compressed, String compressedContent) { }

    public static WorkingMemoryResponse of(WorkingMemory memory) {
        List<Message> sent = new ArrayList<>();
        for (int i = 0; i < memory.messages().size(); i++) {
            WorkingMemory.Entry entry = memory.messages().get(i);
            if (!entry.sent()) {
                continue;
            }
            TurnMessage.Type type = entry.message().type();
            boolean said = type == TurnMessage.Type.USER || type == TurnMessage.Type.ASSISTANT;
            sent.add(new Message(said ? "CHAT" : type.name(), type == TurnMessage.Type.USER ? "USER" : "AGENT",
                    i + 1, entry.tokens(), entry.message().text(), entry.sentAs() != null, entry.sentAs()));
        }
        return new WorkingMemoryResponse(memory.instructions(), memory.instructionTokens(), sent,
                memory.totalTokens(),
                memory.contextWindowTokens().isPresent() ? memory.contextWindowTokens().getAsInt() : null);
    }
}
