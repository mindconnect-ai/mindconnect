package ai.mindconnect.user.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.port.out.UserToolRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a user keeps in their own tool account: adding one, changing it,
 * taking it away.
 *
 * <p>Two rules live here because the UI is not the only caller:
 *
 * <ol>
 *   <li><b>A name is claimed once.</b> Two bindings that would appear to the
 *       model under one name are two tools with the same name, and the second
 *       would silently win. Adding such a name is refused with the reason.</li>
 *   <li><b>An approval is only ever tightened.</b> {@code needsApproval} is
 *       stored as {@code true} or as nothing; a user asking for <em>fewer</em>
 *       questions than their agent asks for is not a preference this layer
 *       carries.</li>
 * </ol>
 */
public class UserToolService {

    private final UserToolRepository tools;
    private final Clock clock;
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public UserToolService(UserToolRepository tools) {
        this(tools, Clock.systemUTC());
    }

    public UserToolService(UserToolRepository tools, Clock clock) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Everything {@code userId} keeps, by the name it appears under. */
    public List<UserTool> of(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return tools.findByUser(userId).stream()
                .sorted(Comparator.comparing(UserTool::effectiveName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** What applies when {@code userId} chats with {@code agentId}. */
    public List<UserTool> forAgent(UserId userId, AgentId agentId) {
        return of(userId).stream().filter(tool -> tool.appliesTo(agentId)).toList();
    }

    public Optional<UserTool> find(UserId userId, UserToolId id) {
        return tools.findById(id).filter(tool -> tool.userId().equals(userId));
    }

    /**
     * Adds a tool to {@code userId}'s account.
     *
     * @param agentId only for this agent, or null for every one they chat with
     * @param alias   the name it appears under; null uses the tool's own name,
     *                which makes this a change to the agent's binding rather
     *                than an extra tool
     * @throws IllegalArgumentException when that name is already taken
     */
    public UserTool add(UserId userId, AgentId agentId, String toolName, String alias,
                        String description, Map<String, Object> params, Boolean needsApproval) {
        return add(userId, agentId, toolName, alias, description, params, needsApproval, Map.of());
    }

    /** A set row with its members set: which tools are in, and which ask first. */
    public UserTool add(UserId userId, AgentId agentId, String toolName, String alias,
                        String description, Map<String, Object> params, Boolean needsApproval,
                        Map<String, UserTool.Member> members) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            String name = alias == null || alias.isBlank() ? toolName : alias.strip();
            requireNameFree(userId, agentId, name, null);
            Instant now = clock.instant();
            UserTool created = new UserTool(UserToolId.random(), userId, agentId, toolName,
                    alias == null || alias.isBlank() ? null : alias.strip(), description,
                    params, true, tighten(needsApproval), members, now, now);
            tools.save(created);
            return created;
        }
    }

    /** Changes one of {@code userId}'s own; empty when the id is not theirs. */
    public Optional<UserTool> update(UserId userId, UserToolId id, String alias, String description,
                                     Map<String, Object> params, Boolean enabled, Boolean needsApproval) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            return find(userId, id).map(stored -> {
                String name = alias == null || alias.isBlank() ? stored.toolName() : alias.strip();
                requireNameFree(userId, stored.agentId(), name, id);
                UserTool updated = stored.with(
                        alias == null || alias.isBlank() ? null : alias.strip(),
                        description, params, enabled, tighten(needsApproval), clock.instant());
                tools.save(updated);
                return updated;
            });
        }
    }

    /** Sets a set row's members anew; empty when the id is not theirs. */
    public Optional<UserTool> updateMembers(UserId userId, UserToolId id, Map<String, UserTool.Member> members) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            return find(userId, id).map(stored -> {
                UserTool updated = stored.withMembers(members, clock.instant());
                tools.save(updated);
                return updated;
            });
        }
    }

    /** Takes it out of the user's account; false when the id is not theirs. */
    public boolean remove(UserId userId, UserToolId id) {
        synchronized (lockFor(userId)) {
            if (find(userId, id).isEmpty()) return false;
            tools.deleteById(id);
            return true;
        }
    }

    /** Switches one of the user's own on or off without touching anything else. */
    public Optional<UserTool> setEnabled(UserId userId, UserToolId id, boolean enabled) {
        synchronized (lockFor(userId)) {
            return find(userId, id).map(stored -> {
                UserTool updated = stored.with(stored.alias(), stored.description(), stored.params(),
                        enabled, stored.needsApproval(), clock.instant());
                tools.save(updated);
                return updated;
            });
        }
    }

    /**
     * Refuses a name a second binding would answer to. Two tools of one name
     * reach the model as one, and which of them runs is then an accident of
     * ordering.
     */
    private void requireNameFree(UserId userId, AgentId agentId, String name, UserToolId except) {
        boolean taken = tools.findByUser(userId).stream()
                .filter(other -> except == null || !other.id().equals(except))
                .filter(other -> sameScope(other.agentId(), agentId))
                .anyMatch(other -> other.effectiveName().equals(name));
        if (taken) {
            throw new IllegalArgumentException(
                    "You already have a tool called \"" + name + "\". Give this one another name.");
        }
    }

    /** Two bindings collide when they can be offered in the same chat. */
    private static boolean sameScope(AgentId one, AgentId other) {
        return one == null || other == null || one.equals(other);
    }

    /** True stays true; false and null both mean "say nothing" — see the class note. */
    private static Boolean tighten(Boolean needsApproval) {
        return Boolean.TRUE.equals(needsApproval) ? Boolean.TRUE : null;
    }

    private Object lockFor(UserId id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }
}
