package ai.mindconnect.agent.runtime.feature.tools;

import ai.mindconnect.agent.runtime.adapter.file.FileToolRepository;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ConfiguredToolRegistry;
import ai.mindconnect.agent.tool.OverlayToolRegistry;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.tool.ToolRepository;

/**
 * Tools for the agents: the SPI registry over whatever {@code mc-agent-tools-*}
 * modules are on the classpath, reading their services and settings from the
 * runtime's registry; the operator's tool settings ({@link ToolRepository},
 * on files) laid over it; dynamic activation; and the executor with every
 * contributed {@link ToolAdvisor}. Without this feature an agent has no tools.
 *
 * <p>A host that starts in phases — a Spring context, whose beans the tools
 * may ask for while it is still refreshing — installs the feature
 * {@link #deferred() deferred} and calls {@link #warmUp()} once it is up.
 */
public class ToolsFeature extends ConfigurableFeature {

    private String disabled;
    private boolean deferred;
    private SpiToolRegistry registry;

    /** Tool names an agent may not use, even when its definition lists them. */
    public ToolsFeature disabled(String... toolNames) {
        changing();
        this.disabled = String.join(",", toolNames);
        return this;
    }

    /** Build the registry without binding the providers; {@link #warmUp()} binds them later. */
    public ToolsFeature deferred() {
        changing();
        this.deferred = true;
        return this;
    }

    /** Binds the providers of a {@link #deferred()} registry — a no-op for an eager one. */
    public void warmUp() {
        if (registry != null) registry.warmUp();
    }

    @Override
    public String name() {
        return "tools";
    }

    @Override
    protected void install(FeatureContext ctx) {
        if (disabled != null) ctx.property("disabledTools", disabled);
        ctx.bean(ToolRegistryRef.class, ToolRegistryRef::new);
        // The operator's decisions about tools — on files only; Postgres keeps none yet.
        if (ctx.persistence() instanceof Persistence.File f) {
            ctx.bean(ToolRepository.class, () -> ctx.require(NamespaceRouting.class).route(ToolRepository.class,
                    ns -> new FileToolRepository(f.dataDir(), ns)));
        }
        ctx.bean(ToolRegistry.class, () -> {
            ToolEnvironment env = ctx.require(ToolEnvironment.class);
            registry = deferred ? SpiToolRegistry.deferred(env) : new SpiToolRegistry(env);
            // What this installation does not offer at all is taken away beneath the
            // operator's decisions, so no tool setting and no agent definition can bring it back.
            ToolRegistry offered = ConfiguredToolRegistry.of(registry, ctx.property("disabledTools").orElse(null));
            // The operator's decisions lie over what the classpath offers.
            ToolRegistry effective = ctx.find(ToolRepository.class)
                    .<ToolRegistry>map(settings -> new OverlayToolRegistry(offered, settings))
                    .orElse(offered);
            ctx.require(ToolRegistryRef.class).set(effective);   // tool_search reads the effective one
            return effective;
        });
        // Whatever another feature lays over the registry (an extension's decorator, say) applies to
        // the bean, not to what the factory above handed the ref — so once the runtime is up, the
        // ref follows the resolved bean, decorators and all. Before that it holds the undecorated one.
        ctx.onStart(() -> ctx.require(ToolRegistryRef.class).set(ctx.require(ToolRegistry.class)));
        // The user's own tools are the layer below the agent's list. Absent on a
        // host that keeps none, and then nothing changes for anybody.
        ctx.bean(DynamicToolActivations.class, () -> new DynamicToolActivations(
                ctx.require(AgentSessionRepository.class), ctx.require(SkillCatalog.class),
                ctx.find(ai.mindconnect.agent.tool.UserToolRoster.class)
                        .orElseGet(ai.mindconnect.agent.tool.UserToolRoster::none)));
        ctx.bean(ToolExecutor.class, () -> new ToolExecutor(ctx.runtime().beans().all(ToolAdvisor.class)));
    }
}
