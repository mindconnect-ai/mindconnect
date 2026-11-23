package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.memory.SummaryStore;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * What the application already keeps, behind the ports of the runtime on
 * the plugin kernel: its agents, skills and tools, its summaries and its
 * LLM call traces. Runs before the runtime's own auto-configuration, so the
 * stores here are the ones it finds.
 */
@AutoConfiguration(
        afterName = "ai.mindconnect.agent.starter.runtime.AgentRuntimeAutoConfiguration",
        beforeName = "ai.mindconnect.agent.starter.runtime.engine.RuntimeEngineAutoConfiguration")
public class RuntimeBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AgentDefinitionMapper agentDefinitionMapper() {
        return new AgentDefinitionMapper();
    }

    /** The application's agents, skills and tools, contributed to the runtime. */
    @Bean
    Plugin applicationSourcePlugin(AgentDefinitionRepository agents, AgentDefinitionMapper mapper,
                                   SkillRepository skills, ToolRegistry tools) {
        return new Plugin() {
            @Override
            public String name() {
                return "application-sources";
            }

            @Override
            public void install(PluginRegistry registry) {
                registry.contribute(AgentResolver.class, new RepositoryAgentResolver(agents, mapper));
                registry.contribute(SkillResolver.class, new RepositorySkillResolver(skills));
                registry.contribute(ToolResolver.class, new RegistryToolResolver(tools));
                // In this application a tool is switched on by the agent naming it; the tool
                // registry already leaves out what the operator disabled.
                registry.contribute(PermissionRuleSource.class,
                        session -> List.of(new PermissionRule(PermissionRule.ANY, PermissionEffect.ALLOW)));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    SummaryStore summaryStore(ConversationSummaryRepository summaries, MessageRepository messages) {
        return new RepositorySummaryStore(summaries, messages);
    }

    @Bean
    @ConditionalOnMissingBean
    LlmCallTraceListener llmCallTraceListener(LlmCallTraceRepository traces, ObjectProvider<TurnService> turns) {
        return new RepositoryLlmCallTraceListener(traces, turns::getObject);
    }
}
