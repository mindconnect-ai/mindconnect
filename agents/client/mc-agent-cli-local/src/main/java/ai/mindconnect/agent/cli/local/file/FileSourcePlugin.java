package ai.mindconnect.agent.cli.local.file;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ModelSettingsResolver;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Everything a harness reads from {@code .mc} folders — agents, skills,
 * model settings, permission rules — and the file tools that work in a
 * session's working directory. Which folders hold for a session is read
 * from the session, so one process can serve sessions in different
 * directories.
 */
public final class FileSourcePlugin implements Plugin {

    private final HarnessDirectories directories;
    private final ObjectMapper json;

    public FileSourcePlugin(HarnessDirectories directories, ObjectMapper json) {
        this.directories = directories;
        this.json = json;
    }

    @Override
    public String name() {
        return "source-file";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(AgentResolver.class, new FileAgentResolver(directories));
        registry.contribute(SkillResolver.class, new FileSkillResolver(directories));
        registry.contribute(ModelSettingsResolver.class, new FileModelSettingsResolver(directories, json));
        registry.contribute(PermissionRuleSource.class, new FilePermissionRuleSource(directories, json));
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(
                new ReadFileTool(directories), new WriteFileTool(directories),
                new ListFilesTool(directories), new SearchFilesTool(directories))));
    }
}
