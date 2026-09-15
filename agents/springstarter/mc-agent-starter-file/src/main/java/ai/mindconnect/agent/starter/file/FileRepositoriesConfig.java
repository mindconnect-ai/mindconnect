package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileLlmCallTraceRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileSkillRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileToolRepository;
import ai.mindconnect.agent.tool.ToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The runtime's repository ports on the file system, all rooted at
 * {@code agentStorageDir/<namespace>} ({@code mindconnect.data.base-dir}).
 * Which namespace a call goes to is the {@link ScopeSupplier}'s answer at
 * that moment: every bean here is a {@link NamespaceRouted} proxy that keeps
 * one file adapter per namespace. Imported by
 * {@link FilePersistenceAutoConfiguration} when {@code mindconnect.persistence}
 * is {@code file}; nothing here decides.
 */
@Configuration
public class FileRepositoriesConfig {

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        return NamespaceRouted.route(AgentDefinitionRepository.class, scope,
                ns -> new FileAgentDefinitionRepository(agentStorageDir, objectMapper, ns));
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        return NamespaceRouted.route(AgentSessionRepository.class, scope,
                ns -> new FileAgentSessionRepository(agentStorageDir, objectMapper, ns));
    }

    /**
     * The operator's decisions about tools. Its absence is a valid state —
     * then nothing deviates from the shipped set — so nothing here creates
     * a file until somebody actually decides something.
     */
    @Bean
    ToolRepository toolRepository(Path agentStorageDir, ScopeSupplier scope) {
        return NamespaceRouted.route(ToolRepository.class, scope,
                ns -> new FileToolRepository(agentStorageDir, ns));
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Path agentStorageDir, ScopeSupplier scope) {
        return NamespaceRouted.route(WorkingMemoryRepository.class, scope,
                ns -> new FileWorkingMemoryRepository(agentStorageDir, ns));
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Path agentStorageDir, ScopeSupplier scope) {
        return NamespaceRouted.route(ConversationSummaryRepository.class, scope,
                ns -> new FileConversationSummaryRepository(agentStorageDir, ns));
    }

    @Bean
    TodoListRepository todoListRepository(Path agentStorageDir, ScopeSupplier scope) {
        return NamespaceRouted.route(TodoListRepository.class, scope,
                ns -> new FileTodoListRepository(agentStorageDir, ns));
    }

    /**
     * The skills this installation stores. The ones a project or a user
     * keeps as {@code SKILL.md} files need no store — they are read where
     * they lie.
     */
    @Bean
    ai.mindconnect.agent.runtime.skill.SkillRepository skillRepository(
            Path agentStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        return NamespaceRouted.route(ai.mindconnect.agent.runtime.skill.SkillRepository.class, scope,
                ns -> new FileSkillRepository(agentStorageDir, objectMapper, ns));
    }

    /**
     * Optional repository for LLM call traces — the turn worker passes it into
     * every LLM round it makes. Lives next to conversation messages — same
     * {@code {base}/{namespace}/conversations/{convId}} root, with traces under a
     * {@code traces/{turnId}/...} subtree.
     */
    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Path agentStorageDir, ScopeSupplier scope,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerSession) {
        return NamespaceRouted.route(LlmCallTraceRepository.class, scope,
                ns -> new FileLlmCallTraceRepository(agentStorageDir, maxPerSession, ns));
    }
}
