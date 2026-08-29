package ai.mindconnect.agent.domain.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * An agent as a session sees it: either a reference to an
 * {@link ai.mindconnect.agent.domain.AgentDefinition} from the registry, or a
 * definition that lives inside the session and nowhere else.
 *
 * <p>The inline variant is what a chat gets when the user picked a model and
 * some tools instead of an agent. It is deliberately <em>not</em> searchable:
 * it is a value object inside the session, so it has no global identity and no
 * index — which is also why changing an agent in the registry can never
 * retroactively change such a chat.
 *
 * <p>It does carry an {@link #id()} all the same. Three things key off an
 * agent id and would otherwise have nothing to work with: the
 * {@code AGENT_USER} workspace scope, the {@code agentDefinitionId} stamped
 * onto every message, and the conversation's participant.
 *
 * <p>A session holds a list of these with exactly one {@link #main()} — one
 * today, several once agents converse with each other in one session.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InlineSessionAgent.class, name = "inline"),
        @JsonSubTypes.Type(value = SessionAgentRef.class,    name = "ref")
})
public sealed interface SessionAgent permits InlineSessionAgent, SessionAgentRef {

    /** Stable id: workspace scope, message attribution, participant. */
    UUID id();

    /** Exactly one per session drives the turn. */
    boolean main();

    /** What the UI calls this agent. */
    String label();
}
