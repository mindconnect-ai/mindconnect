package ai.mindconnect.agent.tool.workspace;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.util.Optional;

/**
 * Decides where a session's files and commands live. Without one in the
 * {@code ToolEnvironment}, the file tools work on this machine under the
 * session's roots, as they always did. With one — a virtual environment
 * server, say — they work on the session's workspace there, and {@code bash}
 * runs in its container.
 *
 * <p>A tool factory asks per call, with the scope of the call and the agent's
 * tool binding, so a binding can name the environment it wants.
 */
public interface WorkspaceProvider {

    /** The files for this call; {@code localRoots} are the roots the tool would use locally. */
    WorkspaceFiles files(ToolCallScope scope, AgentTool tool, FileRoots localRoots);

    /** Where commands for this call run; empty when this provider runs none. */
    Optional<CommandRunner> commands(ToolCallScope scope, AgentTool tool);

    /** The provider's files when one is bound, this machine's under {@code localRoots} otherwise. */
    static WorkspaceFiles localOrProvided(Optional<WorkspaceProvider> provider, ToolCallScope scope, AgentTool tool,
                                          FileRoots localRoots) {
        return provider.map(p -> p.files(scope, tool, localRoots)).orElseGet(() -> WorkspaceFiles.local(localRoots));
    }
}
