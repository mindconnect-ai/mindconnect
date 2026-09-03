package ai.mindconnect.agent.domain.view;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.common.Namespace;

import java.time.Instant;
import java.util.UUID;

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

    UUID id();

    UUID agentDefinitionId();

    Namespace namespace();

    String userId();

    UUID conversationId();

    String title();

    SessionStatus status();

    Instant startedAt();

    Instant completedAt();

    UUID parentSessionId();
}
