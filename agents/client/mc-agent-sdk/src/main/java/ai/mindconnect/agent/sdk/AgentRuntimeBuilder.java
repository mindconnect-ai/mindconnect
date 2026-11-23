package ai.mindconnect.agent.sdk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuard;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuardPlugin;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuard;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuardPlugin;
import ai.mindconnect.agent.sdk.inmemory.InMemoryAgentResolver;
import ai.mindconnect.agent.sdk.inmemory.InMemoryGuardResolver;
import ai.mindconnect.agent.sdk.inmemory.InMemorySkillResolver;
import ai.mindconnect.agent.sdk.inmemory.InMemorySourcePlugin;
import ai.mindconnect.agent.sdk.inmemory.InMemoryToolResolver;
import ai.mindconnect.agent.runtime.capabilities.memory.InMemorySummaryStore;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.ApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.InMemoryApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.prompt.PromptPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.InMemoryToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolSearchPlugin;
import ai.mindconnect.agent.runtime.capabilities.trace.LoggingTraceSink;
import ai.mindconnect.agent.runtime.capabilities.trace.TracePlugin;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmGatewayModelPlugin;
import ai.mindconnect.agent.sdk.inmemory.InMemorySessionStorePlugin;
import ai.mindconnect.agent.runtime.engine.adapter.prompt.PebblePromptPlugin;
import ai.mindconnect.agent.runtime.engine.service.RuntimePlugin;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

/**
 * Puts a runtime together. Without a word it is complete but for the model:
 * sessions kept in memory, every default capability on, tools allowed
 * unless a rule says otherwise. Everything a caller says here either hands
 * something over — an agent, a tool, a rule — or swaps a part.
 */
public final class AgentRuntimeBuilder {

    private final List<AgentDefinition> agents = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final List<SkillDefinition> skills = new ArrayList<>();
    private final List<InputGuard> inputGuards = new ArrayList<>();
    private final List<OutputGuard> outputGuards = new ArrayList<>();
    private final List<PermissionRule> rules = new ArrayList<>();
    private final List<Plugin> more = new ArrayList<>();
    private final Set<Class<? extends Plugin>> left = new HashSet<>();
    private Plugin model;
    private Plugin sessionStore = new InMemorySessionStorePlugin();
    private PermissionEffect unruledTools = PermissionEffect.ALLOW;
    private NamespaceProvider namespaces = NamespaceProvider.fixed("default");
    private ApprovalMemory approvals;

    AgentRuntimeBuilder() {
    }

    /** The model, through the LLM gateway. */
    public AgentRuntimeBuilder model(LlmChat chat) {
        this.model = new LlmGatewayModelPlugin(chat, LlmCallTraceListener.NONE, model -> java.util.Optional.empty());
        return this;
    }

    /** The model, behind the runtime's own port: a test's script, a gateway of one's own. */
    public AgentRuntimeBuilder model(ModelGateway gateway) {
        this.model = provides("model", registry -> registry.provide(ModelGateway.class, () -> gateway));
        return this;
    }

    public AgentRuntimeBuilder agent(AgentDefinition agent) {
        agents.add(agent);
        return this;
    }

    public AgentRuntimeBuilder tool(Tool tool) {
        tools.add(tool);
        return this;
    }

    public AgentRuntimeBuilder skill(SkillDefinition skill) {
        skills.add(skill);
        return this;
    }

    public AgentRuntimeBuilder inputGuard(InputGuard guard) {
        inputGuards.add(guard);
        return this;
    }

    public AgentRuntimeBuilder outputGuard(OutputGuard guard) {
        outputGuards.add(guard);
        return this;
    }

    /** A rule about a tool; rules are read in the order they were given and the first that covers a tool decides. */
    public AgentRuntimeBuilder permission(String toolName, PermissionEffect effect) {
        rules.add(new PermissionRule(toolName, effect));
        return this;
    }

    /** What holds for a tool no rule covers. Allowed unless said otherwise: the program chose its tools itself. */
    public AgentRuntimeBuilder unruledTools(PermissionEffect effect) {
        this.unruledTools = effect;
        return this;
    }

    /**
     * Who says which namespace the running code works in. One fixed
     * namespace unless said otherwise: a program that embeds the runtime
     * for itself has no tenants.
     */
    public AgentRuntimeBuilder namespaces(NamespaceProvider namespaces) {
        this.namespaces = namespaces;
        return this;
    }

    /** Where "allow for good" is kept, in place of the memory of this process. */
    public AgentRuntimeBuilder approvalMemory(ApprovalMemory approvals) {
        this.approvals = approvals;
        return this;
    }

    /** Where sessions are kept, in place of the memory of this process. */
    public AgentRuntimeBuilder sessionStore(Plugin providesSessionStore) {
        this.sessionStore = providesSessionStore;
        return this;
    }

    /** One more plugin: another source of agents, a capability of one's own. */
    public AgentRuntimeBuilder plugin(Plugin plugin) {
        more.add(plugin);
        return this;
    }

    /** Leaves a default capability out, e.g. {@code SessionTitlePlugin.class} to save its model call. */
    public AgentRuntimeBuilder without(Class<? extends Plugin> capability) {
        left.add(capability);
        return this;
    }

    public AgentRuntime build() {
        if (model == null) {
            throw new IllegalStateException("A runtime needs a model: call model(...) before build()");
        }
        LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());
        PluginRegistry registry = new DefaultPluginRegistry();
        NamespaceProvider namespaces = this.namespaces;
        List<Plugin> plugins = new ArrayList<>(List.of(
                provides("namespace", into -> into.provide(NamespaceProvider.class, () -> namespaces)),
                sessionStore,
                model,
                new RuntimePlugin(queue, new InMemorySharedStateStore()),
                new InMemorySourcePlugin(
                        new InMemoryAgentResolver(agents),
                        new InMemorySkillResolver(skills),
                        new InMemoryToolResolver(tools),
                        new InMemoryGuardResolver(inputGuards, outputGuards)),
                new PebblePromptPlugin(),
                new PromptPlugin(),
                new SkillPlugin(),
                new ToolSearchPlugin(new InMemoryToolActivation(namespaces)),
                new SubAgentPlugin(),
                new PermissionPlugin(unruledTools,
                        approvals != null ? approvals : new InMemoryApprovalMemory(namespaces)),
                new MemoryPlugin(new InMemorySummaryStore(namespaces)),
                new TracePlugin(new LoggingTraceSink()),
                new InputGuardPlugin(),
                new OutputGuardPlugin(),
                new SessionTitlePlugin()));
        plugins.addAll(more);
        plugins.stream().filter(plugin -> !left.contains(plugin.getClass())).forEach(registry::install);
        List<PermissionRule> given = List.copyOf(rules);
        registry.contribute(PermissionRuleSource.class, session -> given);
        registry.start();
        return new AgentRuntime(registry, queue);
    }

    private static Plugin provides(String name, Consumer<PluginRegistry> install) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void install(PluginRegistry registry) {
                install.accept(registry);
            }
        };
    }
}
