package ai.mindconnect.agent.runtime.domain.view;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.agent.UserId;

import java.time.Instant;

/**
 * A session as a list shows it: every scalar of {@link AgentSession}, none
 * of its collections. The aggregate itself is a header — the sidebar can
 * take either — but a header is not a session: it cannot be saved, so a
 * store may build one from a few columns without ever reading the document.
 *
 * <p>Rule for this package: only accessors the aggregate has under the same
 * name. A field the aggregate cannot supply alone is a read model, not a
 * view, and belongs with the port that computes it.
 */
public interface AgentSessionHeader {

    SessionId id();

    AgentId agentDefinitionId();


    UserId userId();

    ConversationId conversationId();

    String title();

    SessionStatus status();

    Instant startedAt();

    Instant completedAt();

    SessionId parentSessionId();
}
