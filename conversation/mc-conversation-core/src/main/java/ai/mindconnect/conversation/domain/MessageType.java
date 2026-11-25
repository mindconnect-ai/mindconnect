package ai.mindconnect.conversation.domain;

/**
 * What a message in a conversation is: text someone wrote for the others to
 * read, or a note of the system. What an agent does while it answers — its
 * tool calls and their results, the approvals it waits for — is the session's
 * and kept with the session, not in the conversation.
 *
 * <p>The values a session's work was stored as here before (tool calls,
 * dispatches, results, approval requests and responses) are gone; the
 * migrations read such messages from their JSON.
 */
public enum MessageType {

    /** Text a participant wrote: what a user asks, what an agent answers. */
    CHAT,

    /** Non-conversational note (seeding, migration markers). Never sent to the LLM. */
    SYSTEM
}
