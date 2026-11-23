package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.management.directory.InstructionFileReader;

import java.nio.file.Path;

/**
 * The standing instruction files of a user and of a project, for a session
 * of this runtime; what is read and how is {@link InstructionFileReader}'s.
 */
public final class InstructionFiles {

    /** The value that turns the user scope off. */
    public static final String OFF = InstructionFileReader.OFF;

    static final int MAX_CHARS = InstructionFileReader.MAX_CHARS;

    private final InstructionFileReader reader;

    private InstructionFiles(InstructionFileReader reader) {
        this.reader = reader;
    }

    public static InstructionFiles of(String userDirTemplate) {
        return new InstructionFiles(InstructionFileReader.of(userDirTemplate));
    }

    /** Project instructions only. */
    public static InstructionFiles projectOnly() {
        return new InstructionFiles(InstructionFileReader.projectOnly());
    }

    static String defaultUserDir() {
        return InstructionFileReader.defaultUserDir();
    }

    /** The user's standing instructions, or an empty string when there are none. */
    public String userSection(AgentSession session) {
        return reader.userSection(userOf(session));
    }

    /** The instructions of the project the session works in, or an empty string. */
    public String projectSection(AgentSession session) {
        return session == null || !session.hasWorkingDir() ? "" : reader.projectSection(session.workingDir());
    }

    Path userDir(AgentSession session) {
        return reader.userDir(userOf(session));
    }

    private static String userOf(AgentSession session) {
        return session == null || session.userId() == null ? null : session.userId().value();
    }

    static String section(String heading, String provenance, Found found) {
        return InstructionFileReader.section(heading, provenance,
                new InstructionFileReader.Found(found.name(), found.content()));
    }

    /** A file that was there and had something in it. */
    record Found(String name, String content) {}
}
