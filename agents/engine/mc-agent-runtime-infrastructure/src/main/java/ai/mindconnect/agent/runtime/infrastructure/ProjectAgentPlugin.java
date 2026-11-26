package ai.mindconnect.agent.runtime.infrastructure;

import ai.mindconnect.agent.runtime.capabilities.subagent.ProjectAgentSource;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Lets an agent hand tasks to the agents the project it works in keeps beside its code. */
public final class ProjectAgentPlugin implements Plugin {

    @Override
    public String name() {
        return "project-agents";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(ProjectAgentSource.class, new ProjectAgentFiles());
    }
}
