package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.adapter.file.FileAgentDefinitionRepository;
import ai.mindconnect.agent.adapter.file.FileAgentSessionRepository;
import ai.mindconnect.agent.adapter.file.FileConversationSummaryRepository;
import ai.mindconnect.agent.adapter.file.FileLlmCallTraceRepository;
import ai.mindconnect.agent.adapter.file.FileTodoListRepository;
import ai.mindconnect.agent.adapter.file.FileWorkingMemoryRepository;
import ai.mindconnect.agent.adapter.file.FileWorkspaceStore;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The runtime's repository ports on the file system, all rooted at
 * {@code agentStorageDir} ({@code mindconnect.data.base-dir}). Imported by
 * {@link FilePersistenceAutoConfiguration} when {@code mindconnect.persistence}
 * is {@code file}; nothing here decides.
 */
@Configuration
public class FileRepositoriesConfig {

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper) {
        return new FileAgentDefinitionRepository(agentStorageDir, objectMapper);
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper) {
        return new FileAgentSessionRepository(agentStorageDir, objectMapper);
    }

    @Bean
    WorkspaceStore workspaceStore(Path agentStorageDir) {
        return new FileWorkspaceStore(agentStorageDir);
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Path agentStorageDir) {
        return new FileWorkingMemoryRepository(agentStorageDir);
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Path agentStorageDir) {
        return new FileConversationSummaryRepository(agentStorageDir);
    }

    @Bean
    TodoListRepository todoListRepository(Path agentStorageDir) {
        return new FileTodoListRepository(agentStorageDir);
    }

    /**
     * Optional repository for LLM call traces — the turn worker passes it into
     * every LLM round it makes. Lives next to conversation messages — same
     * {@code {base}/conversations/{convId}} root, with traces under a
     * {@code traces/{turnId}/...} subtree.
     */
    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Path agentStorageDir,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerSession) {
        return new FileLlmCallTraceRepository(agentStorageDir.resolve("conversations"), maxPerSession);
    }
}
