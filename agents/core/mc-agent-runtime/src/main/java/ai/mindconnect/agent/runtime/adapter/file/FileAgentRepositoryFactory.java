package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** The agent runtime's repositories as files under {@code <baseDir>/<namespace>}, the namespace the scope names per call. */
public class FileAgentRepositoryFactory implements AgentRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ScopeSupplier scope;

    private final int maxTracesPerConversation;

    public FileAgentRepositoryFactory(Path baseDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        this(baseDir, objectMapper, scope, FileLlmCallTraceRepository.DEFAULT_MAX_PER_SESSION);
    }

    /** {@code maxTracesPerConversation}: the LLM call traces kept per conversation, oldest dropped; 0 keeps all. */
    public FileAgentRepositoryFactory(Path baseDir, ObjectMapper objectMapper, ScopeSupplier scope, int maxTracesPerConversation) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.scope = scope;
        this.maxTracesPerConversation = maxTracesPerConversation;
    }

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new FileAgentDefinitionRepository(baseDir, objectMapper, scope); }
    @Override public AgentSessionRepository agentSessionRepository() { return new FileAgentSessionRepository(baseDir, objectMapper, scope); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new FileWorkingMemoryRepository(baseDir, scope); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new FileConversationSummaryRepository(baseDir, scope); }
    @Override public TodoListRepository todoListRepository() { return new FileTodoListRepository(baseDir, scope); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new FileLlmCallTraceRepository(baseDir, maxTracesPerConversation, scope); }
    @Override public SkillRepository skillRepository() { return new FileSkillRepository(baseDir, objectMapper, scope); }
    @Override public UserMemoryRepository userMemoryRepository() { return new FileUserMemoryRepository(baseDir, scope); }
}
