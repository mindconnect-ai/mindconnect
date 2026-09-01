package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.service.InlineAgentTools;
import ai.mindconnect.agent.service.stream.SessionChannels;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The sub-agent side of a {@code run_agent(s)} tool call — spawning, awaiting
 * and resuming child turns. Approval needs NO special path here any more:
 * the gate sits in the tool task itself ({@code ToolCallWorker}), so a
 * sub-agent waiting for a human is simply a sub-turn that takes longer —
 * this class just keeps awaiting it.
 *
 * <p>Extracted from {@link ToolCallWorker} so that class stays "execute one
 * call, write one result" — everything that makes delegation special lives
 * here, behind one method: {@link #run}.
 */
final class SubAgentCalls {

    private static final Logger log = LoggerFactory.getLogger(SubAgentCalls.class);

    /** How long a tool task waits for one sub-agent turn before giving up on it. */
    private static final Duration SUB_AGENT_TIMEOUT = Duration.ofHours(2);

    private final ConversationManager conversationManager;
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSessionService sessionService;
    private final MemoryStrategyFactory memoryStrategyFactory;
    private final SessionChannels sessionChannels;

    /** Set once the queue exists — needed to await sub-agent turns. */
    private volatile TaskQueue queue;

    SubAgentCalls(ConversationManager conversationManager,
                  AgentDefinitionRepository definitionRepository,
                  AgentSessionService sessionService,
                  MemoryStrategyFactory memoryStrategyFactory,
                  SessionChannels sessionChannels) {
        this.conversationManager = conversationManager;
        this.definitionRepository = definitionRepository;
        this.sessionService = sessionService;
        this.memoryStrategyFactory = memoryStrategyFactory;
        this.sessionChannels = sessionChannels;
    }

    void attach(TaskQueue queue) {
        this.queue = queue;
    }

    String run(TaskContext ctx, AgentSession parentSession, AgentDefinition caller, UUID parentTurnId,
                                int parentDepth, Consumer<StreamEvent> parentStream,
                                String toolName, String toolCallId, Map<String, Object> arguments) {
        return InlineAgentTools.RUN_AGENTS.equals(toolName)
                ? dispatchBatch(ctx, parentSession, caller, parentTurnId, parentDepth, parentStream,
                        toolCallId, arguments)
                : dispatchOne(ctx, parentSession, caller, parentTurnId, parentDepth, parentStream,
                        toolCallId, 0, arguments);
    }

    /**
     * {@code run_agent}: one child turn, awaited — RESUMABLY. The sub-session
     * (found by {@code parentToolCallId}) is the anchor: a fresh dispatch
     * creates it, a resumed execution finds it and derives its CURRENT turn
     * from the sub-conversation (the newest user-sent message carries the
     * turnId — a fresh turn starts with the CHAT, a resumed one with the
     * APPROVAL_RESPONSE). The submit is idempotent by task id, so one code
     * path serves first run, resume and crash repair alike.
     *
     *
     * <p>Never throws for tool failures — errors become text.
     */
    private String dispatchOne(TaskContext ctx, AgentSession parentSession, AgentDefinition caller,
                               UUID parentTurnId,
                               int parentDepth, Consumer<StreamEvent> parentStream,
                               String toolCallId, int slot, Map<String, Object> arguments) {
        if (parentDepth + 1 > AgentTurnWorker.MAX_DEPTH) {
            return "Error: Sub-agent depth limit (" + AgentTurnWorker.MAX_DEPTH + ") exceeded";
        }
        String agentName = arg(arguments, "name");
        String message = arg(arguments, "message");
        if (agentName == null || agentName.isBlank()) {
            return "Error: name is required. Received keys: " + arguments.keySet()
                    + ". Use {\"name\": \"<agent-name>\", \"message\": \"<task>\"}";
        }
        if (message == null || message.isBlank()) {
            return "Error: message is required";
        }
        // The roster is a permission, not a display filter: an agent that
        // cannot see a name in list_agents cannot reach it by knowing the name
        // from somewhere else either. Refused by its own word rather than as
        // "not found", so a trace says which of the two it was.
        if (caller != null && !caller.mayCall(agentName)) {
            return "Error: agent '" + agentName + "' is not available to you. "
                    + "Available: " + String.join(", ", caller.effectiveCallableAgents());
        }

        // Deterministic per call AND slot: all run_agents batch tasks share the
        // parent's toolCallId (the UI groups them under one card by that id),
        // so the slot index must join the key or N batch tasks would collide
        // on one sub-session and collapse into a single run.
        UUID cardId = UUID.nameUUIDFromBytes(
                ("subagent:" + toolCallId + "#" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID childTurnId = UUID.nameUUIDFromBytes(
                ("subturn:" + toolCallId + "#" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // The resume anchor: the sub-session this exact call+slot spawned —
        // identified by its FIRST turn id, which is deterministic.
        AgentSession subSession = sessionService.subSessions(parentSession.id()).stream()
                .filter(sub -> toolCallId.equals(sub.parentToolCallId()))
                .filter(sub -> childTurnId.equals(firstUserTurnId(sub.conversationId())))
                .findFirst().orElse(null);

        if (subSession == null) {
            Namespace namespace = parentSession.namespace();
            AgentDefinition target;
            try {
                target = definitionRepository.findByNamespace(namespace).stream()
                        .filter(a -> a.name().equalsIgnoreCase(agentName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No agent named '" + agentName + "' in namespace " + namespace.value()));
            } catch (Exception e) {
                log.warn("Failed to resolve sub-agent '{}': {}", agentName, e.getMessage());
                return "Error: " + e.getMessage();
            }
            try {
                subSession = sessionService.openChat(target.id(), namespace, parentSession.userId(),
                        parentSession.id(), parentTurnId, toolCallId);
            } catch (Exception e) {
                log.warn("Failed to open sub-session for '{}': {}", agentName, e.getMessage());
                parentStream.accept(new StreamEvent.SubAgentError(cardId, agentName, e.getMessage()));
                return "Error: " + e.getMessage();
            }
            MemoryStrategy targetStrategy = memoryStrategyFactory.create(target);
            AgentTurnWorker.appendUserMessage(conversationManager, subSession.conversationId(),
                    message, childTurnId, targetStrategy.resolveTokenCounter(target));
            parentStream.accept(new StreamEvent.SubAgentStarted(
                    cardId, agentName, parentDepth + 1, subSession.id(), message));
        }

        // The current turn EXECUTION, derived from the sub-conversation: the
        // turnId is stable across approval resumes, the run counts them —
        // together they name the task. submitChild is idempotent by that id,
        // so this is the first submit, a no-op on resume, and a crash repair.
        var subHistory = conversationManager.loadCompleteHistory(subSession.conversationId());
        UUID currentTurn = subHistory.currentTurnId().orElse(null);
        if (currentTurn == null) {
            return "Error: sub-session has no turn to run";
        }
        int currentRun = subHistory.currentRun();
        Subscription mirror = sessionChannels.subscribeTurn(subSession.id(), currentTurn,
                event -> parentStream.accept(new StreamEvent.SubAgentEvent(cardId, event)));
        try {
            String childTaskId = ctx.submitChild(AgentTurnWorker.submission(
                    currentTurn, currentRun, subSession.id(), parentDepth + 1, parentTurnId));
            TaskRecord done = queue.await(childTaskId, SUB_AGENT_TIMEOUT);
            if (done.status() == TaskStatus.COMPLETED) {
                String response = done.result() == null ? "" : done.result();
                parentStream.accept(new StreamEvent.SubAgentDone(
                        cardId, agentName, subSession.id(), response));
                return response;
            }
            String error = done.failure() != null ? done.failure().message() : done.status().name();
            parentStream.accept(new StreamEvent.SubAgentError(cardId, agentName, error));
            return "Error: " + error;
        } catch (Exception e) {
            log.warn("Sub-agent '{}' failed: {}", agentName, e.getMessage());
            parentStream.accept(new StreamEvent.SubAgentError(cardId, agentName, e.getMessage()));
            return "Error: " + e.getMessage();
        } finally {
            mirror.close();
        }
    }

    /** {@code run_agents}: submit all, await all — they run concurrently on the queue. */
    @SuppressWarnings("unchecked")
    private String dispatchBatch(TaskContext ctx, AgentSession parentSession, AgentDefinition caller,
                                 UUID parentTurnId,
                                 int parentDepth, Consumer<StreamEvent> parentStream,
                                 String toolCallId, Map<String, Object> arguments) {
        if (parentDepth + 1 > AgentTurnWorker.MAX_DEPTH) {
            return "Error: Sub-agent depth limit (" + AgentTurnWorker.MAX_DEPTH + ") exceeded";
        }
        Object rawTasks = arguments.get("tasks");
        if (!(rawTasks instanceof List<?> taskList) || taskList.isEmpty()) {
            return "Error: 'tasks' must be a non-empty array of {\"name\": \"<agent>\", \"message\": \"<task>\"}."
                    + " Received keys: " + arguments.keySet();
        }
        log.info("run_agents fan-out count={} depth={}", taskList.size(), parentDepth + 1);

        List<Thread> threads = new ArrayList<>(taskList.size());
        List<String> results = new ArrayList<>(java.util.Collections.nCopies(taskList.size(), null));
        for (int i = 0; i < taskList.size(); i++) {
            final int index = i;
            final Object rawTask = taskList.get(i);
            threads.add(Thread.ofVirtual().start(() -> {
                String result = rawTask instanceof Map<?, ?> taskMap
                        ? dispatchOne(ctx, parentSession, caller, parentTurnId, parentDepth, parentStream,
                                toolCallId, index, (Map<String, Object>) taskMap)
                        : "Error: task " + (index + 1) + " is not an object with 'name' and 'message'";
                synchronized (results) {
                    results.set(index, result);
                }
            }));
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < taskList.size(); i++) {
            String agentName = taskList.get(i) instanceof Map<?, ?> m
                    ? String.valueOf(m.get("name")) : "task-" + (i + 1);
            String result = results.get(i) != null ? results.get(i) : "Error: interrupted";
            out.append("### Result from agent \"").append(agentName)
                    .append("\" (task ").append(i + 1).append(" of ").append(taskList.size()).append(")\n")
                    .append(result).append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    /** The conversation's FIRST turn id — the deterministic identity of the sub-session's spawn. */
    private UUID firstUserTurnId(UUID conversationId) {
        var turns = conversationManager.loadCompleteHistory(conversationId).turns();
        return turns.isEmpty() ? null : turns.get(0).turnId();
    }

    private static String arg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
