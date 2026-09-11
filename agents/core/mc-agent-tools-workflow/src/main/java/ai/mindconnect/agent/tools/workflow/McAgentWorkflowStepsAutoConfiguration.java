package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.workflow.step.AgentInvoker;
import ai.mindconnect.agent.tools.workflow.step.AgentInvokers;
import ai.mindconnect.agent.tools.workflow.step.ToolInvoker;
import ai.mindconnect.agent.tools.workflow.step.ToolInvokers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutionException;

/**
 * Publishes the {@link AgentInvoker} that lets agent-call workflow steps run a
 * full chat turn in-process. Steps are instantiated by the ServiceLoader-driven
 * workflow context factory (no Spring access), so the invoker is handed over
 * through the static {@link AgentInvokers} holder.
 */
@AutoConfiguration
@ConditionalOnBean(AgentChatService.class)
public class McAgentWorkflowStepsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McAgentWorkflowStepsAutoConfiguration.class);

    /** userId that agent-call sessions are opened under — visible in the sessions list. */
    private static final String WORKFLOW_USER = "workflow";

    @Bean
    AgentInvoker workflowAgentInvoker(AgentSessionService sessionService,
                                      AgentChatService chatService,
                                      AgentDefinitionRepository definitionRepository) {
        AgentInvoker invoker = new AgentInvoker() {
            @Override
            public String call(String agentName, String message) {
                AgentDefinition def = definitionRepository.findByName(agentName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No agent named '" + agentName + "'"));
                AgentSession session = sessionService.openChat(def.id(), UserId.of(WORKFLOW_USER));
                ChatTurnHandle handle = chatService.submitChat(session.id(), message, event -> { });
                try {
                    return handle.result().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handle.cancel();
                    throw new RuntimeException("Agent call to '" + agentName + "' was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException(
                            "Agent call to '" + agentName + "' failed: " + cause.getMessage(), cause);
                }
            }

            @Override
            public String upsertAgent(java.util.Map<String, Object> spec) {
                String name = str(spec.get("name"));
                if (name == null) {
                    throw new IllegalArgumentException("agentSpec needs a 'name'");
                }
                String systemPrompt = str(spec.get("systemPrompt"));
                String description = str(spec.get("description"));
                String llmConfigName = str(spec.get("llmConfigName"));

                AgentDefinition base = definitionRepository.findByName(name)
                        .map(existing -> existing.withBasicFields(name,
                                description != null ? description : existing.description(),
                                systemPrompt != null ? systemPrompt : existing.systemPrompt(),
                                existing.welcomeMessage(),
                                llmConfigName != null ? llmConfigName : existing.llmConfigName()))
                        .orElseGet(() -> AgentDefinition.create(name,
                                description, systemPrompt, null, llmConfigName));

                java.util.List<ai.mindconnect.agent.tool.AgentTool> tools = new java.util.ArrayList<>();
                if (spec.get("tools") instanceof java.util.List<?> rawTools) {
                    for (Object raw : rawTools) {
                        if (!(raw instanceof java.util.Map<?, ?> toolMap)) continue;
                        String toolName = str(toolMap.get("name"));
                        if (toolName == null) continue;
                        java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
                        if (toolMap.get("overrides") instanceof java.util.Map<?, ?> ov) {
                            ov.forEach((k, v) -> overrides.put(String.valueOf(k), v));
                        }
                        // Shortcut: "tool" may sit next to "name" instead of inside overrides.
                        if (toolMap.get("tool") instanceof String alias && !alias.isBlank()) {
                            overrides.putIfAbsent("tool", alias);
                        }
                        tools.add(new ai.mindconnect.agent.tool.AgentTool(
                                ai.mindconnect.agent.tool.AgentToolId.random(), toolName,
                                str(toolMap.get("description")), overrides));
                    }
                }
                definitionRepository.save(base.withTools(tools));
                log.info("Upserted inline agent '{}' with {} tool(s)", name, tools.size());
                return name;
            }

            private String str(Object value) {
                return value instanceof String s && !s.isBlank() ? s : null;
            }
        };
        AgentInvokers.set(invoker);
        log.info("Agent-call workflow step wired to the in-process agent runtime");
        return invoker;
    }

    @Bean
    ToolInvoker workflowToolInvoker(ToolRegistry toolRegistry) {
        ToolInvoker invoker = new ToolInvoker() {
            @Override
            public String call(String toolName, java.util.Map<String, Object> arguments) {
                Tool tool = toolRegistry
                        .resolve(AgentTool.of(toolName),
                                ToolCallScope.detached(UserId.of(WORKFLOW_USER)))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No tool named '" + toolName + "' is resolvable"
                                + " (known: " + toolRegistry.knownToolNames() + ")"));
                return tool.execute(arguments);
            }

            @Override
            public java.util.Set<String> knownToolNames() {
                return toolRegistry.knownToolNames();
            }
        };
        ToolInvokers.set(invoker);
        log.info("Tool-call workflow step wired to the runtime's tool registry");
        return invoker;
    }
}
