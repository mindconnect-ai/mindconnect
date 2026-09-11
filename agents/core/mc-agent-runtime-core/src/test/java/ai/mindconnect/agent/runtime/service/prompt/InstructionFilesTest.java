package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standing instructions reach the prompt from two places: the user's own,
 * from a directory that is one path on a desktop and one per user on a
 * server, and the project's, from the session's working directory.
 */
class InstructionFilesTest {

    @TempDir
    Path project;

    @TempDir
    Path users;

    private AgentSession session(String userId, Path workingDir) {
        AgentSession s = AgentSession.start(AgentId.random(), UserId.of(userId), ConversationId.random());
        return workingDir == null ? s : s.withWorkingDir(workingDir.toString());
    }

    @Test
    void theProjectFileInTheWorkingDirectoryLandsInThePrompt() throws Exception {
        Files.writeString(project.resolve("AGENTS.md"),
                "# Build\n\nRun `mvn -q verify` before saying anything is done.\n");

        String section = InstructionFiles.projectOnly().projectSection(session("u", project));

        assertThat(section)
                .startsWith("\n\n## Project instructions\n")
                .contains("From `AGENTS.md` in the working directory")
                .contains("This is the project speaking")
                .contains("Run `mvn -q verify` before saying anything is done.");
    }

    @Test
    void oneDirectoryForEveryone_theDesktopCase() throws Exception {
        Path home = Files.createDirectories(users.resolve("home/.mindconnect"));
        Files.writeString(home.resolve("AGENTS.md"), "Answer in German.");
        InstructionFiles files = InstructionFiles.of(home.toString());

        String section = files.userSection(session("anyone", null));

        assertThat(section)
                .startsWith("\n\n## User instructions\n")
                .contains("hold in every project")
                .contains("the project wins")
                .contains("Answer in German.");
        assertThat(files.userSection(session("someone-else", null)))
                .as("the same file whoever is asking").isEqualTo(section);
    }

    @Test
    void oneDirectoryPerUser_theServerCase() throws Exception {
        Files.writeString(Files.createDirectories(users.resolve("ada")).resolve("AGENTS.md"), "Ada's way.");
        Files.writeString(Files.createDirectories(users.resolve("bob")).resolve("PROMPT.md"), "Bob's way.");
        InstructionFiles files = InstructionFiles.of(users + "/{user}");

        assertThat(files.userSection(session("ada", null))).contains("Ada's way.").doesNotContain("Bob's way.");
        assertThat(files.userSection(session("bob", null))).contains("Bob's way.");
        assertThat(files.userSection(session("carol", null))).as("no file of her own").isEmpty();
    }

    @Test
    void aUserIdMayNotClimbOutOfItsDirectory() throws Exception {
        Files.writeString(Files.createDirectories(users.resolve("secrets")).resolve("AGENTS.md"), "not yours");
        InstructionFiles files = InstructionFiles.of(users + "/{user}");

        assertThat(files.userDir(session("../secrets", null))).as("a climb out").isNull();
        assertThat(files.userDir(session("a/b", null))).as("a separator").isNull();
        assertThat(files.userDir(null)).as("no session, so no user name at all").isNull();
        assertThat(files.userSection(session("../secrets", null))).isEmpty();
        assertThat(files.userDir(session("ada", null))).isEqualTo(users.resolve("ada"));
    }

    @Test
    void theUserScopeCanBeTurnedOff() throws Exception {
        Path home = Files.createDirectories(users.resolve("home"));
        Files.writeString(home.resolve("AGENTS.md"), "never read");

        assertThat(InstructionFiles.of(InstructionFiles.OFF).userSection(session("u", null))).isEmpty();
        assertThat(InstructionFiles.projectOnly().userSection(session("u", null))).isEmpty();
        assertThat(InstructionFiles.of(home.toString()).userSection(session("u", null)))
                .as("and on again when a directory is named").contains("never read");
    }

    @Test
    void theDefaultIsTheAccountsOwnMindconnectDirectory() {
        assertThat(InstructionFiles.defaultUserDir())
                .isEqualTo(System.getProperty("user.home") + "/.mindconnect");
        assertThat(InstructionFiles.of("  ").userDir(session("u", null)))
                .as("blank means the default, not off")
                .isEqualTo(Path.of(InstructionFiles.defaultUserDir()));
    }

    @Test
    void theStandardNameWinsOverTheOthers() throws Exception {
        InstructionFiles files = InstructionFiles.projectOnly();
        Files.writeString(project.resolve("CLAUDE.md"), "the Claude one");
        assertThat(files.projectSection(session("u", project)))
                .contains("the Claude one").contains("From `CLAUDE.md`");

        Files.writeString(project.resolve("PROMPT.md"), "the Mindconnect one");
        assertThat(files.projectSection(session("u", project)))
                .contains("the Mindconnect one").doesNotContain("the Claude one");

        Files.writeString(project.resolve("AGENTS.md"), "the standard one");
        assertThat(files.projectSection(session("u", project)))
                .contains("the standard one")
                .as("one file, not all three").doesNotContain("the Mindconnect one");
    }

    @Test
    void nothingToSayStaysSilent() throws Exception {
        InstructionFiles files = InstructionFiles.projectOnly();
        assertThat(files.projectSection(session("u", project))).as("no project file").isEmpty();

        Files.writeString(project.resolve("AGENTS.md"), "   \n\n  ");
        assertThat(files.projectSection(session("u", project)))
                .as("an empty file is not a section").isEmpty();

        Files.createDirectories(project.resolve("sub/PROMPT.md"));
        assertThat(files.projectSection(session("u", project.resolve("sub"))))
                .as("a directory of that name").isEmpty();

        assertThat(files.projectSection(session("u", null))).as("no working directory").isEmpty();
        assertThat(files.projectSection(null)).isEmpty();
        assertThat(files.projectSection(session("u", project.resolve("gone"))))
                .as("directory not there").isEmpty();
    }

    @Test
    void aLongFileIsCutAndSaysSo() {
        String section = InstructionFiles.section("Project instructions", "From somewhere.",
                new InstructionFiles.Found("AGENTS.md", "x".repeat(InstructionFiles.MAX_CHARS + 500)));

        assertThat(section).contains("x".repeat(200))
                .contains("[…] AGENTS.md is longer than " + InstructionFiles.MAX_CHARS
                        + " characters and was cut here.");
        assertThat(section.length()).isLessThan(InstructionFiles.MAX_CHARS + 500);
    }
}
