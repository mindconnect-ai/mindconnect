package ai.mindconnect.agent.tools.toolsearch;

import ai.mindconnect.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped set of tools the agent discovered at runtime via
 * {@code tool_search}. The agent's configured tool list stays authoritative
 * and untouched — activations are an additive, in-memory layer that
 * {@code AgentChatService.resolveTools} merges in on every round, so a tool
 * found mid-turn is offered to the LLM from the next round on.
 *
 * <p>Persisted on the {@link ai.mindconnect.agent.domain.AgentSession}
 * itself: activations survive restarts and are deleted with the session.
 */
public final class DynamicToolActivations {

    private final ai.mindconnect.agent.port.out.AgentSessionRepository sessions;

    public DynamicToolActivations(ai.mindconnect.agent.port.out.AgentSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** Marks {@code toolNames} usable for {@code sessionId}, persisted on the session. */
    public void activate(UUID sessionId, Collection<String> toolNames) {
        if (sessionId == null || toolNames.isEmpty()) {
            return;
        }
        sessions.findById(sessionId).ifPresent(session ->
                sessions.save(session.withActivatedTools(toolNames)));
    }

    /** The names activated for this session; empty set when none (or unknown session). */
    public Set<String> activated(UUID sessionId) {
        if (sessionId == null) {
            return Set.of();
        }
        return sessions.findById(sessionId)
                .map(session -> (Set<String>) new java.util.LinkedHashSet<>(session.activatedTools()))
                .orElse(Set.of());
    }

    /**
     * The tool references to resolve for one round, honouring the agent's
     * tool-search configuration:
     * <ul>
     *   <li>non-deferred configured tools — always offered;</li>
     *   <li>deferred configured tools — only once a search activated them
     *       (with their configured overrides and pins intact);</li>
     *   <li>synthetic references for activated registry finds beyond the
     *       configured list (no overrides — an operator who wants pins on a
     *       searchable tool configures it explicitly);</li>
     *   <li>the {@code tool_search} tool itself when the agent enables it,
     *       carrying its search space (deferred names + group filter) as
     *       overrides so the factory needs no definition lookup.</li>
     * </ul>
     */
    public List<AgentTool> effectiveRefs(ai.mindconnect.agent.domain.AgentDefinition def, UUID sessionId) {
        Set<String> activated = activated(sessionId);
        List<AgentTool> refs = new ArrayList<>();
        List<String> deferredNames = new ArrayList<>();
        for (AgentTool tool : def.tools()) {
            if (!tool.deferred()) {
                refs.add(tool);
                continue;
            }
            deferredNames.add(tool.name());
            if (activated.contains(tool.name())) {
                refs.add(tool);
            }
        }
        for (String name : activated) {
            if (def.tools().stream().noneMatch(t -> name.equals(t.name()))) {
                refs.add(AgentTool.of(def.id(), name));
            }
        }
        var search = def.toolSearchOrOff();
        if (search.enabled()) {
            refs.add(AgentTool.of(def.id(), "tool_search", null, Map.of(
                    "assigned", List.copyOf(deferredNames),
                    "groups", List.copyOf(search.groups()))));
        }
        return refs;
    }
}
