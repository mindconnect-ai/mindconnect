package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** The agent runtime's repositories as files under {@code <baseDir>/<namespace>}. */
public class FileAgentRepositoryFactory implements AgentRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final Namespace namespace;

    private final int maxTracesPerConversation;

    public FileAgentRepositoryFactory(Path baseDir, ObjectMapper objectMapper, Namespace namespace) {
        this(baseDir, objectMapper, namespace, FileLlmCallTraceRepository.DEFAULT_MAX_PER_SESSION);
    }

    /** {@code maxTracesPerConversation}: the LLM call traces kept per conversation, oldest dropped; 0 keeps all. */
    public FileAgentRepositoryFactory(Path baseDir, ObjectMapper objectMapper, Namespace namespace, int maxTracesPerConversation) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.maxTracesPerConversation = maxTracesPerConversation;
    }

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new FileAgentDefinitionRepository(baseDir, objectMapper, namespace); }
    @Override public AgentSessionRepository agentSessionRepository() { return new FileAgentSessionRepository(baseDir, objectMapper, namespace); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new FileWorkingMemoryRepository(baseDir, namespace); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new FileConversationSummaryRepository(baseDir, namespace); }
    @Override public TodoListRepository todoListRepository() { return new FileTodoListRepository(baseDir, namespace); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new FileLlmCallTraceRepository(baseDir, maxTracesPerConversation, namespace); }
    @Override public SkillRepository skillRepository() { return new FileSkillRepository(baseDir, objectMapper, namespace); }
}
