package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileLlmCallTraceRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.adapter.file.FileWorkspaceStore;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The runtime's repository ports on the file system, all rooted at
 * {@code agentStorageDir/<namespace>} ({@code mindconnect.data.base-dir},
 * {@code mindconnect.namespace}). Imported by
 * {@link FilePersistenceAutoConfiguration} when {@code mindconnect.persistence}
 * is {@code file}; nothing here decides.
 */
@Configuration
public class FileRepositoriesConfig {

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        return new FileAgentDefinitionRepository(agentStorageDir, objectMapper, namespace);
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        return new FileAgentSessionRepository(agentStorageDir, objectMapper, namespace);
    }

    @Bean
    WorkspaceStore workspaceStore(Path agentStorageDir, Namespace namespace) {
        return new FileWorkspaceStore(agentStorageDir, namespace);
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Path agentStorageDir, Namespace namespace) {
        return new FileWorkingMemoryRepository(agentStorageDir, namespace);
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Path agentStorageDir, Namespace namespace) {
        return new FileConversationSummaryRepository(agentStorageDir, namespace);
    }

    @Bean
    TodoListRepository todoListRepository(Path agentStorageDir, Namespace namespace) {
        return new FileTodoListRepository(agentStorageDir, namespace);
    }

    /**
     * Optional repository for LLM call traces — the turn worker passes it into
     * every LLM round it makes. Lives next to conversation messages — same
     * {@code {base}/{namespace}/conversations/{convId}} root, with traces under a
     * {@code traces/{turnId}/...} subtree.
     */
    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Path agentStorageDir, Namespace namespace,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerSession) {
        return new FileLlmCallTraceRepository(agentStorageDir, maxPerSession, namespace);
    }
}
