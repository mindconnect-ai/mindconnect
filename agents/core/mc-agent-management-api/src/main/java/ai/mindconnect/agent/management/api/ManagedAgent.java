package ai.mindconnect.agent.management.api;

import ai.mindconnect.agent.AgentId;

import java.time.Instant;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;

/**
 * An agent as it is kept and edited: the definition the runtime runs, and
 * beside it what only a person looking at a list of agents needs.
 *
 * @param id             {@code null} for an agent not stored yet; the
 *                       management gives it one
 * @param definition     what a session and a turn are handed — name,
 *                       instructions, model, tools, and in its metadata what
 *                       the capabilities read
 * @param group          the heading an agent is filed under in lists and
 *                       pickers; {@code null} for none
 * @param icon           the name of the symbol shown with the agent;
 *                       {@code null} to let the surface pick
 * @param welcomeMessage what an empty chat with this agent shows
 * @param version        counts the changes; a change says which version it
 *                       was made from, and is refused when that is not the
 *                       one stored any more
 */
public record ManagedAgent(
        AgentId id,
        AgentDefinition definition,
        String group,
        String icon,
        String welcomeMessage,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    /** Metadata of the definition: the agent's id, where the tools read whose agent calls them. */
    public static final String AGENT_ID = "id";

    /** Whether an agent is in use. Only an {@link #ACTIVE} one is found by name when another agent calls it. */
    public enum Status {
        DRAFT,
        ACTIVE,
        DEPRECATED
    }

    /** A new active agent around a definition, to be handed to {@code AgentManagement.create}. */
    public static ManagedAgent of(AgentDefinition definition) {
        return new ManagedAgent(null, definition, null, null, null, Status.ACTIVE, null, null, 0);
    }

    public ManagedAgent withDefinition(AgentDefinition changed) {
        return new ManagedAgent(id, changed, group, icon, welcomeMessage, status, createdAt, updatedAt, version);
    }

    public ManagedAgent withStatus(Status changed) {
        return new ManagedAgent(id, definition, group, icon, welcomeMessage, changed, createdAt, updatedAt, version);
    }

    /** How the agent is shown: its group, its symbol and what an empty chat with it says. */
    public ManagedAgent shownAs(String group, String icon, String welcomeMessage) {
        return new ManagedAgent(id, definition, group, icon, welcomeMessage, status, createdAt, updatedAt, version);
    }
}
