package ai.mindconnect.agent.runtime.capabilities.memory;

/**
 * What was said in a session up to one message, in a few paragraphs.
 *
 * @param text             the summary
 * @param throughMessageId the id of the last message it covers: the model
 *                         is sent the summary and what came after
 */
public record ConversationSummary(String text, String throughMessageId) { }
