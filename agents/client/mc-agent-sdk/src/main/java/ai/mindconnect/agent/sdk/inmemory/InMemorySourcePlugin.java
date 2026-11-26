package ai.mindconnect.agent.sdk.inmemory;

import ai.mindconnect.agent.runtime.capabilities.guard.GuardResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Agents, skills, tools and guards a program hands over in code. They all come
 * the same way — a resolver each — so that a caller learns one pattern, and the
 * resolvers stand beside those of other sources: files, a store.
 */
public final class InMemorySourcePlugin implements Plugin {

    private final InMemoryAgentResolver agents;
    private final InMemorySkillResolver skills;
    private final InMemoryToolResolver tools;
    private final InMemoryGuardResolver guards;

    public InMemorySourcePlugin(InMemoryAgentResolver agents, InMemorySkillResolver skills,
                                InMemoryToolResolver tools, InMemoryGuardResolver guards) {
        this.agents = agents;
        this.skills = skills;
        this.tools = tools;
        this.guards = guards;
    }

    @Override
    public String name() {
        return "source-in-memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(AgentResolver.class, agents);
        registry.contribute(SkillResolver.class, skills);
        registry.contribute(ToolResolver.class, tools);
        registry.contribute(GuardResolver.class, guards);
    }
}
