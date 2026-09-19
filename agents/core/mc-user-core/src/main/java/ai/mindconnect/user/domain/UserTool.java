package ai.mindconnect.user.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool a user keeps in their own account.
 *
 * <p>The same shape as an agent's tool binding, one layer further down — and
 * one record for both things a person wants: a binding for a tool the agent
 * does not list <b>adds</b> it to their chats, one for a tool it does
 * <b>changes</b> it for them alone.
 *
 * <p><b>Narrower than the agent's binding on purpose.</b> There is no
 * {@code baseDir}, no {@code network}, no result cap: those are operating
 * decisions and stay with whoever runs the installation. What is here is what
 * belongs to the person — which of their accounts a tool runs on, what it is
 * called in their chats, and whether they want it at all.
 *
 * @param id             the binding's own id
 * @param userId         whose it is
 * @param agentId        the agent it applies to; <b>null means every agent</b>
 *                       they chat with
 * @param toolName       the registry name it refers to — what is resolved
 * @param alias          the name the model sees. Set it and this is an
 *                       <em>additional</em> tool rather than a change to the
 *                       agent's: that is how the same tool appears twice, once
 *                       per mailbox. Null means it carries the tool's own name
 * @param description    what the model is told about it; null inherits
 * @param params         the parameters this binding fixes, by parameter name —
 *                       in practice {@code {"account": "arbeit"}}, naming one of
 *                       the user's connections. Flat: the wrapping into an
 *                       agent binding's {@code overrides.params} happens where
 *                       the binding is built, not here.
 *                       A pinned parameter is written over whatever the model
 *                       passed and is taken out of the schema it sees
 * @param enabled        false hides the tool from this user's chats; null and
 *                       true both mean "offered", because switching one
 *                       <em>on</em> that the installation switched off is not
 *                       a user's to decide and the registry refuses it anyway
 * @param needsApproval  true asks the user before every call. False is not
 *                       stored as a decision: an approval may be tightened by
 *                       any layer and relaxed by none
 * @param createdAt      when they added it
 * @param updatedAt      when they last changed it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserTool(
        UserToolId id,
        UserId userId,
        AgentId agentId,
        String toolName,
        String alias,
        String description,
        Map<String, Object> params,
        Boolean enabled,
        Boolean needsApproval,
        Map<String, Member> members,
        Instant createdAt,
        Instant updatedAt
) {

    public UserTool {
        members = members == null ? Map.of() : Map.copyOf(members);
        Objects.requireNonNull(id, "A user tool needs an id");
        Objects.requireNonNull(userId, "A user tool needs a user");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("A user tool needs the name of a tool");
        }
        if (alias != null && alias.isBlank()) alias = null;
        if (description != null && description.isBlank()) description = null;
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** A tool the user adds to every agent they chat with, under its own name. */
    /**
     * One tool of a set row, as the user set it: whether it is in, and whether
     * it asks first. Absent means in, and asking only if the row asks.
     *
     * @param enabled      false takes this tool out of the set for this user
     * @param needsApproval true makes this tool ask; null follows the row
     */
    public record Member(boolean enabled, Boolean needsApproval) {
        public static Member on() { return new Member(true, null); }
    }

    public static UserTool added(UserId userId, String toolName, Instant now) {
        return new UserTool(UserToolId.random(), userId, null, toolName, null, null,
                Map.of(), true, null, Map.of(), now, now);
    }

    /** The name this binding appears under — its alias, else the tool's own. */
    public String effectiveName() {
        return alias != null ? alias : toolName;
    }

    /**
     * True when this is an extra tool rather than a change to one the agent
     * already lists: it carries a name of its own, so both can be offered side
     * by side — one mailbox each.
     */
    public boolean isAdditional() {
        return alias != null;
    }

    /** False only when the user switched it off; see {@link #enabled()}. */
    public boolean offered() {
        return enabled == null || enabled;
    }

    /** True when the user asked to be asked; a stored false says nothing. */
    public boolean tightensApproval() {
        return Boolean.TRUE.equals(needsApproval);
    }

    /** Applies to {@code agent} — either because it names it, or because it names none. */
    public boolean appliesTo(AgentId agent) {
        return agentId == null || agentId.equals(agent);
    }

    /**
     * This set row as one of its members: the same account, switch and
     * approval, under the member's own name, with an id that is stable and
     * unique — the row's id plus the tool. Only meaningful for a set row.
     */
    public UserTool member(String memberName) {
        Member setting = members.getOrDefault(memberName, Member.on());
        Boolean memberEnabled = enabled == null ? (setting.enabled() ? null : Boolean.FALSE)
                : enabled && setting.enabled();
        Boolean asks = Boolean.TRUE.equals(needsApproval) || Boolean.TRUE.equals(setting.needsApproval())
                ? Boolean.TRUE : needsApproval;
        return new UserTool(UserToolId.of(id.value() + "-" + memberName.replaceAll("[^A-Za-z0-9_-]", "_")),
                userId, agentId, memberName, null, description, params, memberEnabled, asks, Map.of(),
                createdAt, updatedAt);
    }

    /** This row with the members set anew — for the edit of a set. */
    public UserTool withMembers(Map<String, Member> newMembers, Instant now) {
        return new UserTool(id, userId, agentId, toolName, alias, description, params, enabled, needsApproval,
                newMembers, createdAt, now);
    }

    public UserTool with(String newAlias, String newDescription, Map<String, Object> newParams,
                         Boolean newEnabled, Boolean newApproval, Instant now) {
        return new UserTool(id, userId, agentId, toolName, newAlias, newDescription, newParams,
                newEnabled, newApproval, members, createdAt, now);
    }

    public UserTool forAgent(AgentId agent, Instant now) {
        return new UserTool(id, userId, agent, toolName, alias, description, params,
                enabled, needsApproval, members, createdAt, now);
    }
}
