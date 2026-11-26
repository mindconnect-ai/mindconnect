package ai.mindconnect.agent.cli.local.file;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.permission.ApprovalMemory;

/**
 * "Allow for good" as a line in the settings file of the working directory:
 * it holds for every later session there, and the user can read and take
 * back what they allowed.
 */
public final class FileApprovalMemory implements ApprovalMemory {

    private final Supplier<SessionStore> sessions;
    private final HarnessDirectories directories;
    private final ObjectMapper json;

    public FileApprovalMemory(Supplier<SessionStore> sessions, HarnessDirectories directories, ObjectMapper json) {
        this.sessions = sessions;
        this.directories = directories;
        this.json = json;
    }

    /** Never by itself: what was remembered is a rule in the settings by now, and the rules are asked first. */
    @Override
    public boolean approved(SessionId sessionId, String toolName) {
        return false;
    }

    @Override
    public void remember(SessionId sessionId, String toolName) {
        sessions.get().find(sessionId).ifPresent(session ->
                SettingsFile.in(directories.writableConfigDirectory(session), json).allow(toolName));
    }
}
