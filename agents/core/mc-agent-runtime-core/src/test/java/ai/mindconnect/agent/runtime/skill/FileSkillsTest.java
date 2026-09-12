package ai.mindconnect.agent.runtime.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skills read off disk: a directory with a {@code SKILL.md} and the files it
 * needs, or a lone {@code .md} for one that needs nothing.
 */
class FileSkillsTest {

    @TempDir
    Path dir;

    @Test
    void aDirectoryWithASkillFileIsASkillAndKnowsWhereItLives() throws Exception {
        Path skillDir = Files.createDirectories(dir.resolve("weekly-report"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Use when writing the weekly report
                ---
                Start from template.md in this directory.
                """);
        Files.writeString(skillDir.resolve("template.md"), "# Week of …");

        var skills = FileSkills.list(dir, SkillSource.PROJECT);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("weekly-report");
        assertThat(skills.get(0).description()).isEqualTo("Use when writing the weekly report");
        assertThat(skills.get(0).directory())
                .as("the model needs the path to open template.md")
                .isEqualTo(skillDir.toAbsolutePath().toString());
        assertThat(skills.get(0).source()).isEqualTo(SkillSource.PROJECT);
    }

    @Test
    void aLoneMarkdownFileIsASkillToo() throws Exception {
        Files.writeString(dir.resolve("release.md"), "Tag, then push the tag.");

        var skills = FileSkills.list(dir, SkillSource.USER);

        assertThat(skills).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("release");
            assertThat(skill.instructions()).isEqualTo("Tag, then push the tag.");
            assertThat(skill.source()).isEqualTo(SkillSource.USER);
        });
    }

    @Test
    void whatIsNotASkillIsSkipped() throws Exception {
        Files.createDirectories(dir.resolve("no-skill-file"));
        Files.writeString(dir.resolve("notes.txt"), "not markdown");
        Files.writeString(dir.resolve("empty.md"), "---\nname: empty\n---\n");
        Files.writeString(dir.resolve("Not A Name.md"), "Body");
        Files.writeString(dir.resolve("good.md"), "Body");

        assertThat(FileSkills.list(dir, SkillSource.USER)).extracting(Skill::name)
                .containsExactly("good");
    }

    @Test
    void aDirectoryThatIsNotThereYieldsNothing() {
        assertThat(FileSkills.list(dir.resolve("nope"), SkillSource.USER)).isEmpty();
        assertThat(FileSkills.list(null, SkillSource.USER)).isEmpty();
        assertThat(FileSkills.project(null)).isEmpty();
        assertThat(FileSkills.project("")).isEmpty();
    }

    @Test
    void aProjectKeepsItsSkillsUnderTheWorkingDirectory() throws Exception {
        Path project = Files.createDirectories(dir.resolve(FileSkills.PROJECT_DIR));
        Files.writeString(project.resolve("house-style.md"), "Short sentences. No exclamation marks.");

        assertThat(FileSkills.project(dir.toString())).extracting(Skill::name)
                .containsExactly("house-style");
    }
}
