package ai.mindconnect.agent.starter.workflow;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;

import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.workflow.WorkflowAgentCalls;
import ai.mindconnect.agent.tools.workflow.step.AgentInvoker;
import ai.mindconnect.agent.tools.workflow.step.AgentInvokers;
import ai.mindconnect.agent.tools.workflow.step.ToolInvoker;
import ai.mindconnect.agent.tools.workflow.step.ToolInvokers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes the {@link AgentInvoker} that lets agent-call workflow steps run a
 * full chat turn in-process. Steps are instantiated by the ServiceLoader-driven
 * workflow context factory (no Spring access), so the invoker is handed over
 * through the static {@link AgentInvokers} holder.
 */
@AutoConfiguration
@ConditionalOnBean(ToolRegistry.class)
public class McAgentWorkflowStepsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McAgentWorkflowStepsAutoConfiguration.class);

    /**
     * @param timeout how long an agent-call step waits for the agent's answer
     *                ({@code mindconnect.workflow.agent-call-timeout}, ISO-8601, default ten minutes)
     */
    @Bean
    @ConditionalOnBean(AgentRuntime.class)
    AgentInvoker workflowAgentInvoker(AgentRuntime runtime,
                                      @org.springframework.beans.factory.annotation.Value(
                                              "${mindconnect.workflow.agent-call-timeout:PT10M}")
                                      java.time.Duration timeout) {
        AgentInvoker invoker = new RuntimeAgentInvoker(runtime, timeout);
        AgentInvokers.set(invoker);
        log.info("Agent-call workflow step wired to the in-process agent runtime");
        return invoker;
    }

    @Bean
    ToolInvoker workflowToolInvoker(ToolRegistry toolRegistry) {
        ToolInvoker invoker = new ToolInvoker() {
            @Override
            public String call(String toolName, java.util.Map<String, Object> arguments) {
                return call(toolName, arguments, null);
            }

            @Override
            public String call(String toolName, java.util.Map<String, Object> arguments,
                               ToolCallScope scope) {
                return call(AgentTool.of(toolName), arguments, scope);
            }

            @Override
            public String call(AgentTool agentTool, java.util.Map<String, Object> arguments,
                               ToolCallScope scope) {
                String toolName = agentTool.name();
                // The scope the run was started with; else one bound to this
                // thread — an upload being ingested, so a file tool lands in the
                // session's directories; else the workflow user, who owns no chat.
                ToolCallScope effective = scope != null ? scope
                        : ToolCallScope.current().orElseGet(() -> ToolCallScope.detached(UserId.of(WorkflowAgentCalls.WORKFLOW_USER)));
                Tool tool = toolRegistry
                        .resolve(agentTool, effective)
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
