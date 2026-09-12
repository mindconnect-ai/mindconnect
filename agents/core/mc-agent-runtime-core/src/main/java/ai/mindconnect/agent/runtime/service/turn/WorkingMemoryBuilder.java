package ai.mindconnect.agent.runtime.service.turn;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.service.prompt.SystemPromptRenderer;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.AuthenticationInfo;

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
                                    AuthenticationInfo auth,
                                    InstructionFiles instructions) {
        return build(renderer, strategy, def, session, auth, instructions, SkillCatalog.none());
    }

    public static WorkingMemory build(PromptRenderer renderer,
                                    MemoryStrategy strategy,
                                    AgentDefinition def,
                                    AgentSession session,
                                    AuthenticationInfo auth,
                                    InstructionFiles instructions,
                                    SkillCatalog skills) {
        String systemText = SystemPromptRenderer.render(renderer, strategy, def, session, auth,
                instructions, skills);
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
