package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.memory.SummaryStore;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
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
    OpenApprovalRecorder openApprovalRecorder(ToolApprovalRepository approvals, ObjectProvider<SessionStore> sessions) {
        return new OpenApprovalRecorder(approvals, sessions::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentDefinitionMapper agentDefinitionMapper() {
        return new AgentDefinitionMapper();
    }

    /** The application's agents, skills and tools, contributed to the runtime. */
    @Bean
    Plugin applicationSourcePlugin(AgentDefinitionRepository agents, AgentDefinitionMapper mapper,
                                   SkillRepository skills, ToolRegistry tools, OpenApprovalRecorder openApprovals) {
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
                // Heard and not hooked in before or after: a call the permission check stops is still heard.
                registry.listen(RuntimeHook.TOOL_CALL, openApprovals::called);
                registry.listen(RuntimeHook.TURN_END, (context, turn) -> openApprovals.ended(context));
            }
        };
    }

    /** The runtime's sessions in its own repository, its messages in the application's, the title carried over. */
    @Bean
    @ConditionalOnMissingBean
    SessionStore sessionStore(SessionRepository sessions, MessageRepository messages,
                              AgentSessionRepository chatSessions) {
        return new ChatSessionStore(new RepositorySessionStore(sessions, messages), chatSessions);
    }

    @Bean
    @ConditionalOnMissingBean
    SummaryStore summaryStore(ConversationSummaryRepository summaries, MessageRepository messages,
                              ObjectProvider<SessionStore> sessions) {
        return new RepositorySummaryStore(summaries, messages, sessions::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    LlmCallTraceListener llmCallTraceListener(LlmCallTraceRepository traces, ObjectProvider<TurnService> turns,
                                              ObjectProvider<SessionStore> sessions) {
        return new RepositoryLlmCallTraceListener(traces, turns::getObject, sessions::getObject);
    }

    /** After the runtime's own services: it calls them. */
    @Bean
    @ConditionalOnMissingBean
    RuntimeChatService runtimeChatService(ObjectProvider<TurnService> turns,
                                          ObjectProvider<SessionService> sessionService,
                                          ObjectProvider<SessionStore> sessions,
                                          AgentSessionRepository chatSessions, AgentDefinitionRepository agents,
                                          AgentDefinitionMapper mapper, AgentChatService earlier) {
        return new RuntimeChatService(turns.getObject(), sessionService.getObject(), sessions.getObject(), chatSessions,
                new SessionAgentResolver(agents), mapper, earlier);
    }
}
