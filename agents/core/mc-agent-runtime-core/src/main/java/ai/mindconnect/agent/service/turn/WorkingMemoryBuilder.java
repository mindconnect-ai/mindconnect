package ai.mindconnect.agent.service.turn;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.service.prompt.SystemPromptRenderer;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.List;

/**
 * Single source of truth for assembling a {@link WorkingMemory} snapshot.
 * Used both during a chat turn (to persist the working-memory snapshot) and
 * by the {@code /memory} endpoint (live read).
 */
public final class WorkingMemoryBuilder {

    private WorkingMemoryBuilder() {}

    public static WorkingMemory build(PromptRenderer renderer,
                                    MemoryStrategy strategy,
                                    AgentDefinition def,
                                    AgentSession session,
                                    AuthenticationInfo auth) {
        String systemText = SystemPromptRenderer.render(renderer, strategy, def, session, auth);
        TokenCounter counter = strategy.resolveTokenCounter(def);
        int systemTokens = counter.countText(systemText);
        List<WorkingMemory.WorkingMemoryMessage> messages = strategy.getWindowMessages(def, session);
        int messageTokens = messages.stream().mapToInt(WorkingMemory.WorkingMemoryMessage::tokens).sum();
        Integer contextWindow = strategy.contextWindowTokens(def);
        return new WorkingMemory(
                systemText,
                systemTokens,
                messages,
                systemTokens + messageTokens,
                counter.getClass().getSimpleName(),
                contextWindow
        );
    }
}
