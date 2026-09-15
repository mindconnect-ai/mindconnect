package ai.mindconnect.agent.runtime.feature.tools;

import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ConfiguredToolRegistry;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRegistryRef;


/**
 * Tools for the agents: the SPI registry over whatever {@code mc-agent-tools-*}
 * modules are on the classpath, reading their services and settings from the
 * runtime's registry, plus dynamic activation and the executor with every
 * contributed {@link ToolAdvisor}. Without this feature an agent has no tools.
 */
public class ToolsFeature extends ConfigurableFeature {

    private String disabled;

    /** Tool names an agent may not use, even when its definition lists them. */
    public ToolsFeature disabled(String... toolNames) {
        changing();
        this.disabled = String.join(",", toolNames);
        return this;
    }

    @Override
    public String name() {
        return "tools";
    }

    @Override
    protected void install(FeatureContext ctx) {
        if (disabled != null) ctx.property("disabledTools", disabled);
        ctx.bean(ToolRegistryRef.class, ToolRegistryRef::new);
        ctx.bean(ToolRegistry.class, () -> {
            ToolRegistry registry = ConfiguredToolRegistry.of(
                    new SpiToolRegistry(ctx.require(ToolEnvironment.class)),
                    ctx.property("disabledTools").orElse(null));
            ctx.require(ToolRegistryRef.class).set(registry);
            return registry;
        });
        ctx.bean(DynamicToolActivations.class, () -> new DynamicToolActivations(
                ctx.require(AgentSessionRepository.class), ctx.require(SkillCatalog.class)));
        ctx.bean(ToolExecutor.class, () -> new ToolExecutor(ctx.runtime().beans().all(ToolAdvisor.class)));
    }
}
