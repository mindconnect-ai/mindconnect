package ai.mindconnect.user.adapter.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolBundles;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.AliasTool;
import ai.mindconnect.agent.tool.PinnedParamsTool;
import ai.mindconnect.agent.tool.UserToolRoster;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.service.UserToolService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lays what a user keeps in their own account over what the agent lists.
 *
 * <p>Nothing new is invented here. A user binding becomes an ordinary
 * {@link AgentTool} with the overrides the runtime already understands — an
 * {@link AliasTool} target so the same tool can appear twice under two names,
 * and {@link PinnedParamsTool} values so each of those names runs on a
 * different account. That is exactly what an operator can write into an agent
 * definition by hand; the difference is that this is derived from
 * <em>their</em> connections instead of typed into a definition several people
 * share.
 *
 * <p>Order of the result is the agent's list first, then whatever the user
 * added — so the tools an agent was built around stay in front of the ones a
 * person brought along.
 */
public class StoredUserToolRoster implements UserToolRoster {

    private final UserToolService tools;
    private final Supplier<ToolRegistry> registry;

    /**
     * @param registry where a set row ({@code group:email}) is expanded into
     *                 its tools — looked up per call, because the registry
     *                 may come to life after this roster does. Null means set
     *                 rows are left out rather than guessed at.
     */
    public StoredUserToolRoster(UserToolService tools, Supplier<ToolRegistry> registry) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.registry = registry == null ? () -> null : registry;
    }

    public StoredUserToolRoster(UserToolService tools) {
        this(tools, null);
    }

    @Override
    public List<AgentTool> apply(UserId userId, AgentId agentId, List<AgentTool> agentRefs) {
        if (userId == null) {
            return agentRefs;                       // work on nobody's behalf keeps the agent's list
        }
        List<UserTool> mine = tools.forAgent(userId, agentId);
        if (mine.isEmpty()) {
            return agentRefs;
        }
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool ref : agentRefs) {
            byName.put(ref.name(), ref);
        }
        ToolBundles bundles = null;
        for (UserTool tool : mine) {
            if (ToolBundles.isSet(tool.toolName())) {
                // One row for the set — the account, the switch and the
                // approval hold for every tool in it, and a tool the group
                // gains later is in from the next chat on.
                if (bundles == null) bundles = bundlesOrNull();
                if (bundles == null) continue;
                for (String member : bundles.expand(List.of(tool.toolName()))) {
                    UserTool asMember = tool.member(member);
                    if (!asMember.offered()) {
                        byName.remove(member);       // the row is off, or this one tool was taken out
                        continue;
                    }
                    byName.put(member, merge(byName.get(member), asMember));
                }
                continue;
            }
            String name = tool.effectiveName();
            if (!tool.offered()) {
                byName.remove(name);                // they do not want it in their chats
                continue;
            }
            byName.put(name, merge(byName.get(name), tool));
        }
        return List.copyOf(byName.values());
    }

    private ToolBundles bundlesOrNull() {
        ToolRegistry found = registry.get();
        return found == null ? null : ToolBundles.of(found, name -> true);
    }

    /**
     * One user binding as an agent binding. {@code base} is what the agent
     * already said about this name, or null when the user is adding a tool the
     * agent does not list.
     */
    private static AgentTool merge(AgentTool base, UserTool mine) {
        Map<String, Object> overrides = new LinkedHashMap<>(base == null ? Map.of() : base.overrides());
        if (!mine.effectiveName().equals(mine.toolName())) {
            // Under a name of its own it has to say which tool it really is.
            overrides.put(AliasTool.OVERRIDE_KEY, mine.toolName());
        }
        if (!mine.params().isEmpty()) {
            overrides.put(PinnedParamsTool.OVERRIDE_KEY, pinned(base, mine));
        }
        return new AgentTool(
                // Stable across calls, and unique: the user binding's own id.
                AgentToolId.of(mine.id().value()),
                mine.effectiveName(),
                mine.description() != null ? mine.description() : base == null ? null : base.description(),
                overrides,
                true,
                base != null && base.deferred(),
                // Tightened by either side, relaxed by neither.
                (base != null && base.needsApproval()) || mine.tightensApproval(),
                base == null ? null : base.maxResultChars());
    }

    /** The agent's pins with the user's over them — theirs wins on the same key. */
    private static Map<String, Object> pinned(AgentTool base, UserTool mine) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null && base.overrides().get(PinnedParamsTool.OVERRIDE_KEY) instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        merged.putAll(mine.params());
        return merged;
    }
}
