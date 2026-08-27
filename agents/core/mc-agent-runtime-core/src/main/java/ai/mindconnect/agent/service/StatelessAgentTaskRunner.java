package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.port.in.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Stateless LLM task runner — no session, no memory, no history.
 * <p>
 * Resolution order for each task:
 * <ol>
 *   <li>Look up an {@link AgentDefinition} by name in the configured namespace.
 *       Uses its {@code llmConfigName} and {@code systemPrompt}.</li>
 *   <li>Fall back to the globally configured default stateless agent
 *       ({@code mindconnect.agent.stateless.llm-config-id}).</li>
 * </ol>
 * <p>
 * The system prompt is rendered through {@link PromptRenderer} so subagent
 * templates can use {@code {{ current_date }}}, conditional blocks, and any
 * extra variables passed to {@link #run(String, String, Map)}.
 */
public class StatelessAgentTaskRunner implements AgentTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(StatelessAgentTaskRunner.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Complete the task concisely and accurately. " +
            "No preamble, no explanation — just the result.";

    private final AgentDefinitionRepository definitionRepository;
    private final LlmChat chatUseCase;
    private final Namespace namespace;
    private final String defaultLlmConfigName;
    private final PromptRenderer promptRenderer;

    public StatelessAgentTaskRunner(AgentDefinitionRepository definitionRepository,
                                    LlmChat chatUseCase,
                                    Namespace namespace,
                                    String defaultLlmConfigName,
                                    PromptRenderer promptRenderer) {
        this.definitionRepository = definitionRepository;
        this.chatUseCase = chatUseCase;
        this.namespace = namespace;
        this.defaultLlmConfigName = defaultLlmConfigName;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public String run(String task, String userMessage) {
        return run(task, userMessage, Map.of());
    }

    @Override
    public String run(String task, String userMessage, Map<String, Object> extraVars) {
        ResolvedAgent agent = resolve(task);

        // Mark the sub-agent in the MDC so log lines emitted during its run are
        // recognisable (parent agent stays in agentName slot — see LoggingContext).
        try (var ignored = ai.mindconnect.common.LoggingContext.subagent(agent.name(), task)) {
            log.debug("Running task '{}' via agent '{}' (configName={})",
                    task, agent.name(), agent.llmConfigName());

            String renderedSystemPrompt = renderSystemPrompt(agent, extraVars);

            List<LlmMessage> messages = List.of(
                    LlmMessage.system(renderedSystemPrompt),
                    LlmMessage.user(userMessage)
            );

            StringBuilder result = new StringBuilder();
            chatUseCase.chatStreaming(
                    LlmRequest.streaming(agent.llmConfigName(), messages),
                    chunk -> {
                        if (chunk instanceof ai.mindconnect.llm.domain.LlmStreamChunk.TextDelta td) {
                            result.append(td.text());
                        }
                        // Stateless tasks ignore tool-call deltas (no tools were
                        // provided) and the terminal Done chunk.
                    }
            );
            return result.toString().trim();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String renderSystemPrompt(ResolvedAgent agent, Map<String, Object> extraVars) {
        // Stateless tasks have no AgentSession / AuthenticationInfo — providers that
        // need them will see null and contribute null values. The Pebble engine
        // skips {% if x %} blocks for null cleanly.
        return promptRenderer.render(
                agent.systemPrompt(),
                agent.def(),     // may be null for the global fallback
                null,            // no session
                null,            // no auth
                extraVars
        );
    }

    private ResolvedAgent resolve(String task) {
        return definitionRepository.findByName(namespace, task)
                .map(d -> {
                    log.debug("Task '{}' resolved to AgentDefinition '{}'", task, d.name());
                    return new ResolvedAgent(d, d.name(), d.llmConfigName(), d.systemPrompt());
                })
                .orElseGet(() -> {
                    if (defaultLlmConfigName == null) {
                        throw new IllegalStateException(
                                "No AgentDefinition found for task '" + task + "' and " +
                                "mindconnect.agent.stateless.llm-config-name is not configured. " +
                                "Either create an AgentDefinition named '" + task + "' or set the property.");
                    }
                    log.debug("No AgentDefinition found for task '{}' — using default stateless agent", task);
                    return new ResolvedAgent(null, "default", defaultLlmConfigName, DEFAULT_SYSTEM_PROMPT);
                });
    }

    private record ResolvedAgent(AgentDefinition def, String name, String llmConfigName, String systemPrompt) {}
}
