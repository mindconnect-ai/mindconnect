package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.session.InlineSessionAgent;
import ai.mindconnect.agent.domain.session.SessionAgentRef;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.DomainException;

import java.time.Instant;

/**
 * Answers the one question every turn starts with: which agent is this
 * session actually running?
 *
 * <p>Three cases, and the runtime must not care which it got:
 * <ul>
 *   <li>a session written before session agents existed — the definition
 *       behind {@code agentDefinitionId}, exactly as before;</li>
 *   <li>a {@link SessionAgentRef} — that definition with this chat's model
 *       and tool choices laid over it;</li>
 *   <li>an {@link InlineSessionAgent} — a definition assembled from the
 *       session itself, which the registry has never heard of.</li>
 * </ul>
 *
 * <p>Every path that runs a turn resolves through here, so a chat behaves the
 * same whether the work happens in {@code AgentChatService} or on the queue.
 */
public class SessionAgentResolver {

    /** Ten rounds, as {@code AgentDefinition.create} has always used. */
    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final AgentDefinitionRepository definitions;

    public SessionAgentResolver(AgentDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    /** The definition this session's main agent amounts to. */
    public AgentDefinition resolve(AgentSession session) {
        var main = session.mainAgent().orElse(null);
        if (main == null) {
            return load(session.agentDefinitionId());
        }
        if (main instanceof InlineSessionAgent inline) {
            return inlineDefinition(session, inline);
        }
        return withOverrides(load(main.id()), (SessionAgentRef) main);
    }

    private AgentDefinition load(java.util.UUID id) {
        return definitions.findById(id)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", id.toString()));
    }

    /**
     * The session's own agent as a definition. The memory config is the
     * system default on purpose — it decides how long chats survive
     * compression and is not something a chat picker should set.
     */
    private static AgentDefinition inlineDefinition(AgentSession session, InlineSessionAgent inline) {
        Instant now = session.startedAt() != null ? session.startedAt() : Instant.now();
        return new AgentDefinition(
                inline.id(), session.namespace(), inline.label(), "", null, null,
                inline.systemPrompt(), null, inline.llmConfigName(),
                DEFAULT_MAX_ITERATIONS, SummarizingWindowConfig.DEFAULT,
                AgentDefinitionStatus.ACTIVE, inline.tools(), java.util.List.of(), null,
                inline.toolSearch(), now, now);
    }

    /**
     * The agent as configured, with this chat's choices on top: model, tools,
     * and — since the roster made detaching costly — the system prompt. See
     * {@link SessionAgentRef} for why that last one stopped being forbidden.
     * Everything the chat does not name stays the agent's own, the roster
     * included.
     */
    private static AgentDefinition withOverrides(AgentDefinition base, SessionAgentRef ref) {
        AgentDefinition out = base;
        if (ref.llmConfigName() != null && !ref.llmConfigName().isBlank()) {
            out = out.withBasicFields(out.name(), out.description(), out.systemPrompt(),
                    out.welcomeMessage(), ref.llmConfigName());
        }
        if (ref.hasPromptOverride()) {
            out = out.withBasicFields(out.name(), out.description(), ref.systemPrompt(),
                    out.welcomeMessage(), out.llmConfigName());
        }
        if (ref.tools() != null) {
            out = out.withTools(ref.tools());
        }
        if (ref.toolSearch() != null) {
            out = out.withToolSearch(ref.toolSearch());
        }
        return out;
    }
}
