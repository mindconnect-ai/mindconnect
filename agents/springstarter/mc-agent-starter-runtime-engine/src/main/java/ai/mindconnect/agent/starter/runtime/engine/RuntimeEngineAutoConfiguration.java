package ai.mindconnect.agent.starter.runtime.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.management.ManagedAgentResolver;
import ai.mindconnect.agent.management.RepositoryAgentManagement;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.FileAgentRepository;
import ai.mindconnect.agent.management.adapter.pg.PgAgentRepository;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.UserProvider;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuardPlugin;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuardPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.InMemorySummaryStore;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.SummaryStore;
import ai.mindconnect.agent.runtime.capabilities.permission.ApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.SessionApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.prompt.PromptPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.SessionToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolSearchPlugin;
import ai.mindconnect.agent.runtime.capabilities.trace.LoggingTraceSink;
import ai.mindconnect.agent.runtime.capabilities.trace.TracePlugin;
import ai.mindconnect.agent.runtime.capabilities.attachment.AttachmentPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction;
import ai.mindconnect.agent.runtime.capabilities.trace.TraceSink;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmGatewayModelPlugin;
import ai.mindconnect.agent.runtime.engine.adapter.pg.PgSessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.prompt.PebblePromptPlugin;
import ai.mindconnect.agent.runtime.engine.adapter.store.FileSessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.engine.service.RuntimePlugin;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;

/**
 * The runtime on the plugin kernel for a Spring Boot application. It builds
 * nothing the application already has: the task queue, the LLM chat, the
 * LLM configurations, the message repository and the scope supplier are the
 * application's beans, and the runtime's turns show in the same queue and
 * write into the same conversations.
 *
 * <p>Approvals and activated tools are kept in the session's metadata.
 * Summaries live in memory and traces go to the log until a bean of that
 * type says otherwise; that is how the application puts its own stores
 * behind them. Every
 * {@link Plugin} bean is installed after the runtime's own, so it can
 * contribute resolvers and replace what was provided.
 *
 * <p>A tool nobody ruled on is denied: on a server nothing runs because
 * nobody said no.
 */
@AutoConfiguration(afterName = "ai.mindconnect.agent.starter.runtime.AgentRuntimeAutoConfiguration")
public class RuntimeEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    NamespaceProvider namespaceProvider(ScopeSupplier scope) {
        return new ScopeNamespaceProvider(scope);
    }

    @Bean
    @ConditionalOnMissingBean
    UserProvider userProvider(ScopeSupplier scope) {
        return new ScopeUserProvider(scope);
    }

    /** On Postgres where the application has it, else as files under the data directory. */
    @Bean
    @ConditionalOnMissingBean
    SessionRepository sessionRepository(ObjectProvider<Sql> sql, ObjectMapper json, NamespaceProvider namespaces,
                                        @Value("${mindconnect.data.base-dir:data}") String dataDirectory) {
        Sql database = sql.getIfAvailable();
        return database != null
                ? new PgSessionRepository(database, namespaces).initSchema()
                : new FileSessionRepository(Path.of(dataDirectory), json, namespaces);
    }

    /** On Postgres where the application has it, else as files under the data directory. */
    @Bean
    @ConditionalOnMissingBean
    AgentRepository agentRepository(ObjectProvider<Sql> sql, ObjectMapper json, NamespaceProvider namespaces,
                                    @Value("${mindconnect.data.base-dir:data}") String dataDirectory) {
        Sql database = sql.getIfAvailable();
        return database != null
                ? new PgAgentRepository(database, namespaces).initSchema()
                : new FileAgentRepository(Path.of(dataDirectory), json, namespaces);
    }

    /** The agents as they are kept and edited; the runtime finds an agent another one calls here. */
    @Bean
    @ConditionalOnMissingBean
    AgentManagement agentManagement(AgentRepository agents) {
        return new RepositoryAgentManagement(agents);
    }

    /** Listing, renaming and deleting sessions, on the same repositories the runtime writes to. */
    @Bean
    @ConditionalOnMissingBean
    SessionManagement sessionManagement(SessionRepository sessions, MessageRepository messages,
                                        AgentRepository agents,
                                        ObjectProvider<SessionDirectoryPolicy> directoryPolicy) {
        return new RepositorySessionManagement(sessions, messages, agents,
                directoryPolicy.getIfAvailable(SessionDirectoryPolicy::unrestricted));
    }

    @Bean
    @ConditionalOnMissingBean
    SessionStore sessionStore(SessionRepository sessions, MessageRepository messages) {
        return new RepositorySessionStore(sessions, messages);
    }

    @Bean
    @ConditionalOnMissingBean
    ApprovalMemory approvalMemory(ObjectProvider<SessionStore> sessions) {
        return new SessionApprovalMemory(sessions::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    SummaryStore summaryStore(NamespaceProvider namespaces) {
        return new InMemorySummaryStore(namespaces);
    }

    @Bean
    @ConditionalOnMissingBean
    ToolActivation toolActivation(ObjectProvider<SessionStore> sessions) {
        return new SessionToolActivation(sessions::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    TraceSink traceSink() {
        return new LoggingTraceSink();
    }

    @Bean
    @ConditionalOnMissingBean
    LlmCallTraceListener llmCallTraceListener() {
        return LlmCallTraceListener.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    PluginRegistry runtimePluginRegistry(RuntimeParts parts, List<Plugin> more) {
        PluginRegistry registry = new DefaultPluginRegistry();
        parts.plugins().forEach(registry::install);
        // The model plugin is a bean too, so that an application can bring its own; it is installed once.
        more.stream().filter(plugin -> !parts.plugins().contains(plugin)).forEach(registry::install);
        registry.start();
        return registry;
    }

    /** The runtime's own plugins, in the order they are installed. */
    @Bean
    RuntimeParts runtimeParts(NamespaceProvider namespaces, UserProvider users, SessionStore sessions,
                              AgentManagement agents, TaskQueue queue,
                              ObjectProvider<SharedStateStore> sharedState, LlmGatewayModelPlugin model,
                              CapabilityPlugins capabilities, ObjectProvider<MediaReader> media) {
        Plugin applicationBeans = new Plugin() {
            @Override
            public String name() {
                return "application-beans";
            }

            @Override
            public void install(PluginRegistry registry) {
                registry.provide(NamespaceProvider.class, () -> namespaces);
                registry.provide(UserProvider.class, () -> users);
                registry.contribute(AgentResolver.class, new ManagedAgentResolver(agents));
                registry.provide(SessionStore.class, () -> sessions);
                media.ifAvailable(reader -> registry.provide(MediaReader.class, () -> reader));
            }
        };
        return new RuntimeParts(Stream.concat(
                Stream.of(applicationBeans, model, new PebblePromptPlugin(),
                        new RuntimePlugin(queue, sharedState.getIfAvailable(InMemorySharedStateStore::new))),
                capabilities.plugins().stream()).toList());
    }

    /** The model through the application's LLM chat; what is known of a model is what its LLM configuration says. */
    @Bean
    @ConditionalOnMissingBean
    LlmGatewayModelPlugin llmGatewayModelPlugin(LlmChat chat, LlmCallTraceListener traces,
                                                LlmConfigRepository configurations) {
        // Resolved: an alias carries neither a context window nor what the model reads.
        return new LlmGatewayModelPlugin(chat, traces, configurations::findResolvedByName);
    }

    @Bean
    CapabilityPlugins capabilityPlugins(ToolActivation activation, ApprovalMemory approvals, SummaryStore summaries,
                                        TraceSink traces) {
        return new CapabilityPlugins(List.of(
                new PromptPlugin(),
                new SkillPlugin(),
                new ToolSearchPlugin(activation),
                new SubAgentPlugin(),
                new PermissionPlugin(PermissionEffect.DENY, approvals),
                new AttachmentPlugin(),
                new MemoryPlugin(summaries),
                new TracePlugin(traces),
                new InputGuardPlugin(),
                new OutputGuardPlugin(),
                new SessionTitlePlugin()));
    }

    /** Summarises a session's earlier turns on request. */
    @Bean
    @ConditionalOnMissingBean
    MemoryCompaction memoryCompaction(PluginRegistry runtimePluginRegistry) {
        return runtimePluginRegistry.get(MemoryCompaction.class);
    }

    @Bean
    @ConditionalOnMissingBean
    SessionService runtimeSessionService(PluginRegistry runtimePluginRegistry) {
        return runtimePluginRegistry.get(SessionService.class);
    }

    @Bean
    @ConditionalOnMissingBean
    TurnService runtimeTurnService(PluginRegistry runtimePluginRegistry) {
        return runtimePluginRegistry.get(TurnService.class);
    }

    /** Not beans of type {@link Plugin} themselves: those are the application's, installed after these. */
    record RuntimeParts(List<Plugin> plugins) { }

    record CapabilityPlugins(List<Plugin> plugins) { }
}
