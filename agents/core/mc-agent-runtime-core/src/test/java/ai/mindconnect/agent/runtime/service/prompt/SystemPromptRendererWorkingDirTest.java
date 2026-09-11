package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The prompt names the working directory — and says nothing when there is none. */
class SystemPromptRendererWorkingDirTest {

    private static AgentSession session() {
        return AgentSession.start(AgentId.random(), UserId.of("u"), ConversationId.random());
    }

    @Test
    void aSessionWithAWorkingDirectoryGetsTheSection() {
        String section = SystemPromptRenderer.workingDirSection(session().withWorkingDir("/home/me/src/app"));

        assertThat(section)
                .startsWith("\n\n## Working directory\n")
                .contains("`/home/me/src/app`")
                .contains("`bash` runs in it");
    }

    @Test
    void additionalDirectoriesAreListed() {
        String section = SystemPromptRenderer.workingDirSection(
                session().withWorkingDir("/work").withAdditionalDirs(java.util.List.of("/lib", "/data")));

        assertThat(section)
                .contains("You are working in `/work`")
                .contains("You may also use these directories, by absolute path:")
                .contains("\n- `/lib`")
                .contains("\n- `/data`");

        String alone = SystemPromptRenderer.workingDirSection(
                session().withAdditionalDirs(java.util.List.of("/lib")));
        assertThat(alone).contains("You may use these directories, by absolute path:").doesNotContain("working in");
    }

    @Test
    void noWorkingDirectoryNoSection() {
        assertThat(SystemPromptRenderer.workingDirSection(session())).isEmpty();
        assertThat(SystemPromptRenderer.workingDirSection(null)).isEmpty();
    }

    @Test
    void anAttachedFileWithACopyOnDiskIsNamedWithItsPath() {
        var onDisk = new ai.mindconnect.agent.runtime.domain.AttachedFile("f1", "spec.docx", null, 10,
                "/home/u/sessions/s1/uploads/spec.docx");
        var indexedOnly = new ai.mindconnect.agent.runtime.domain.AttachedFile("f2", "notes.md", null, 5);

        String both = SystemPromptRenderer.attachedFilesSection(
                session().withAttachedFiles(java.util.List.of(onDisk, indexedOnly)));
        assertThat(both)
                .contains("- spec.docx (Word document) — on disk at `/home/u/sessions/s1/uploads/spec.docx`")
                .contains("- notes.md (Markdown)\n")
                .contains("A file with a path is a file on disk: open it by that path")
                .contains("A file without a path is NOT on the filesystem");

        String onlyOnDisk = SystemPromptRenderer.attachedFilesSection(
                session().withAttachedFiles(java.util.List.of(onDisk)));
        assertThat(onlyOnDisk).doesNotContain("NOT on the filesystem");
    }

    @Test
    void anUploadInTheChatsOwnDirectoryIsNamedTheWayTheToolsTakeIt() {
        // The model types what the prompt shows, and `uploads/spec.docx` is
        // what file_read, document_outline and grep_document want here.
        var upload = new ai.mindconnect.agent.runtime.domain.AttachedFile("f1", "spec.docx", null, 10,
                "/home/u/sessions/s1/uploads/spec.docx");

        String inOwnDir = SystemPromptRenderer.attachedFilesSection(session()
                .withWorkingDir("/home/u/sessions/s1")
                .withAttachedFiles(java.util.List.of(upload)));
        assertThat(inOwnDir)
                .contains("on disk at `/home/u/sessions/s1/uploads/spec.docx`")
                .contains("i.e. `uploads/spec.docx` from the working directory");

        // A chat working in a project keeps the absolute path — the short one
        // would point somewhere else.
        String inProject = SystemPromptRenderer.attachedFilesSection(session()
                .withWorkingDir("/home/u/project")
                .withAttachedFiles(java.util.List.of(upload)));
        assertThat(inProject)
                .contains("on disk at `/home/u/sessions/s1/uploads/spec.docx`")
                .doesNotContain("from the working directory");
    }
}
