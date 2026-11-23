package ai.mindconnect.agent.cli.local;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.cli.local.file.FileApprovalMemory;
import ai.mindconnect.agent.cli.local.file.FileSessionStore;
import ai.mindconnect.agent.cli.local.file.FileSourcePlugin;
import ai.mindconnect.agent.cli.local.file.FileAgentResolver;
import ai.mindconnect.agent.cli.local.file.HarnessDirectories;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.sdk.AgentRuntimeBuilder;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The runtime as the local harness wants it: the SDK's runtime with files
 * in place of memory — sessions under the home directory, agents, skills,
 * settings and permissions from {@code .mc} folders — and an agent to
 * start with for a directory that defines none.
 */
public final class LocalRuntime {

    /** The agent used when none is named: enough to look around a project and change it. */
    public static final String DEFAULT_AGENT = "assistant";

    private static final AgentDefinition BUILT_IN = new AgentDefinition(DEFAULT_AGENT,
            "A coding assistant working in the project directory.",
            "You are a coding assistant working in the user's project directory. Look at the files "
                    + "before you answer questions about them, and say what you changed.",
            ModelSettings.NONE,
            ToolBinding.allOf("read_file", "write_file", "list_files", "search_files", "ask_user"),
            30, Map.of());

    private final Path home;

    public LocalRuntime(Path home) {
        this.home = home;
    }

    /** A builder that lacks only the model. */
    public AgentRuntimeBuilder builder() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        HarnessDirectories directories = new HarnessDirectories(home);
        FileSessionStore sessions = new FileSessionStore(home.resolve(".mc").resolve("sessions"), json);
        return AgentRuntime.builder()
                .sessionStore(provides("session-store-file", registry ->
                        registry.provide(SessionStore.class, () -> sessions)))
                .approvalMemory(new FileApprovalMemory(() -> sessions, directories, json))
                // Writing is the one thing here that cannot be taken back by reading again.
                .permission("write_file", PermissionEffect.ASK)
                .plugin(new FileSourcePlugin(directories, json))
                // After the files: an agent of this name in a .mc folder replaces the built-in one.
                .plugin(provides("agent-built-in", registry -> registry.contribute(AgentResolver.class,
                        (name, session) -> DEFAULT_AGENT.equals(name) ? Optional.of(BUILT_IN) : Optional.empty())));
    }

    /**
     * A session that works in a directory: where its files are, and whose
     * {@code .mc} folder holds for it. The agent is looked up once, here —
     * the session keeps it as it was found.
     */
    public SessionSpec sessionIn(Path workingDirectory, UserId userId, String agentName) {
        Path directory = workingDirectory.toAbsolutePath();
        HarnessDirectories directories = new HarnessDirectories(home);
        AgentDefinition agent = new FileAgentResolver(directories)
                .resolve(agentName, directories.configDirectories(directory))
                .or(() -> DEFAULT_AGENT.equals(agentName) ? Optional.of(BUILT_IN) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("There is no agent " + agentName));
        return new SessionSpec(userId, null, agent,
                Map.of(HarnessDirectories.WORKING_DIRECTORY, directory.toString()));
    }

    private static Plugin provides(String name, java.util.function.Consumer<PluginRegistry> install) {
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
