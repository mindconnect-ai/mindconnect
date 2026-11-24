package ai.mindconnect.agent.management.directory;

import java.nio.file.Path;

import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Where the sessions of an installation work: each user's home, the
 * directories a session may be pointed at, a session's own directory and
 * its removal with the session — and what the model is told about them.
 */
public final class DirectoryPlugin implements Plugin {

    private final Path dataDir;
    private final String usersHome;
    private final String workingDirRoot;
    private final boolean choice;
    private final String instructionsUserDir;

    /**
     * @param dataDir             where the homes go when no template is given: a folder per namespace
     * @param usersHome           the template of a user's home, with {@code {user}}; {@code null} for the default
     * @param workingDirRoot      the one tree sessions may work in; {@code null} for each user's home
     * @param choice              whether a user may point a session at another directory
     * @param instructionsUserDir where a user's standing instructions are read from; blank for the default
     */
    public DirectoryPlugin(Path dataDir, String usersHome, String workingDirRoot, boolean choice,
                           String instructionsUserDir) {
        this.dataDir = dataDir;
        this.usersHome = usersHome;
        this.workingDirRoot = workingDirRoot;
        this.choice = choice;
        this.instructionsUserDir = instructionsUserDir;
    }

    @Override
    public String name() {
        return "directories";
    }

    @Override
    public void install(PluginRegistry registry) {
        // Under the namespace the current call works in, so every namespace has its own homes.
        registry.provide(UserHome.class, () -> usersHome != null && !usersHome.isBlank() ? UserHome.of(usersHome)
                : UserHome.underCurrent(() -> dataDir.resolve(registry.get(ScopeSupplier.class).namespace().value())
                        .toAbsolutePath()));
        registry.provide(WorkingDirPolicy.class, () -> {
            // The home template is asked per call, not now: there is no namespace yet.
            WorkingDirPolicy policy = workingDirRoot == null || workingDirRoot.isBlank()
                    ? WorkingDirPolicy.withinCurrent(registry.get(UserHome.class)::template)
                    : WorkingDirPolicy.within(workingDirRoot);
            return policy.withChoice(choice);
        });
        registry.provide(WorkingDirBrowser.class, () -> new WorkingDirBrowser(registry.get(WorkingDirPolicy.class)));
        registry.provide(SessionDirectoryPolicy.class, () -> new RootedSessionDirectoryPolicy(
                registry.get(WorkingDirPolicy.class), registry.get(UserHome.class)));
        // A deleted session leaves its directory: files are not this plugin's to remove.
        // A host that wants them gone contributes a SessionDirectoryCleanup (the starter: a property).
        new DirectoryPromptPlugin(InstructionFileReader.of(instructionsUserDir == null ? "" : instructionsUserDir))
                .install(registry);
    }
}
