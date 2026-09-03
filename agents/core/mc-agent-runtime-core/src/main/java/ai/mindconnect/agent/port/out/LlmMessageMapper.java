package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.ContextTokenBudget;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.message.domain.Message;

import java.util.List;

/**
 * How stored messages read to the model. The one place where the
 * conversation's records — user text, assistant text, tool calls and
 * results, and what their metadata means — become the messages of an LLM
 * request. Every memory strategy hands its selection of messages here.
 *
 * <p>This is the read-side extension point, and deliberately the only one:
 * a host that wants the model to see its messages differently — an extra
 * line for a metadata field, another role mapping, content blocks for a
 * provider that has them — replaces or decorates this port. There is no
 * per-message-type hook; the mapper sees them all.
 *
 * <p>The default is {@code MessageToLlmMessageMapper}.
 */
public interface LlmMessageMapper {

    /**
     * Maps a list of stored messages to LLM-ready messages, within the
     * per-message token limit of {@code budget}. The session is the live
     * state a record may have to be read against — what is attached to the
     * chat <em>now</em>, not what was when the message was written.
     */
    List<LlmMessage> toMessages(List<Message> messages, AgentDefinition def, AgentSession session,
                                ContextTokenBudget budget);

    /**
     * The text the model reads for one stored message — what {@link #toMessages}
     * would put in its content. For the working-memory view, so the admin
     * sees the same text and the same token count as the model.
     */
    default String modelText(Message message, AgentDefinition def, AgentSession session) {
        return message.content();
    }
}
