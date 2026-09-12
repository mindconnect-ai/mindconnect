package ai.mindconnect.agent.runtime.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A skill as it is written down and read back: the {@code SKILL.md} form
 * other tools already use, and the name rules a model has to be able to type.
 */
class SkillTest {

    @Test
    void aSkillFileBecomesASkill() {
        Skill skill = Skill.fromMarkdown("ignored", """
                ---
                name: weekly-report
                description: Use when writing the weekly status report
                tools: file_read, vector_search
                ---
                ## Weekly report

                1. Read last week's report.
                """, SkillSource.PROJECT, "/repo/.mindconnect/skills/weekly-report");

        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("weekly-report");
        assertThat(skill.description()).isEqualTo("Use when writing the weekly status report");
        assertThat(skill.tools()).containsExactly("file_read", "vector_search");
        assertThat(skill.instructions()).startsWith("## Weekly report").contains("Read last week's report.");
        assertThat(skill.source()).isEqualTo(SkillSource.PROJECT);
        assertThat(skill.directory()).isEqualTo("/repo/.mindconnect/skills/weekly-report");
        assertThat(skill.id().value()).as("a file skill is identified by its own name").isEqualTo("weekly-report");
    }

    @Test
    void theFileNameStandsInWhenTheFrontMatterNamesNone() {
        Skill skill = Skill.fromMarkdown("release", "Tag, then push the tag.",
                SkillSource.USER, null);

        assertThat(skill.name()).isEqualTo("release");
        assertThat(skill.description()).isEmpty();
        assertThat(skill.instructions()).isEqualTo("Tag, then push the tag.");
    }

    @Test
    void aFileWithoutInstructionsIsNoSkill() {
        assertThat(Skill.fromMarkdown("empty", "---\nname: empty\n---\n\n  \n", SkillSource.USER, null))
                .as("front matter alone says when to use a skill that does nothing")
                .isNull();
        assertThat(Skill.fromMarkdown("empty", "", SkillSource.USER, null)).isNull();
    }

    @Test
    void aNameNoModelCouldTypeIsRefused() {
        assertThat(Skill.fromMarkdown("My Skill", "Do the thing.", SkillSource.USER, null))
                .as("a space is not a name the model can pass to the tool")
                .isNull();

        assertThatThrownBy(() -> Skill.create("My Skill", "d", "i", List.of()).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .as("the name is reported as it was normalised, with the rule it broke")
                .hasMessageContaining("my skill")
                .hasMessageContaining("lower-case letters, digits and dashes");
    }

    @Test
    void aNameIsFoldedToLowerCase() {
        assertThat(Skill.create("Weekly-Report", "d", "i", List.of()).name()).isEqualTo("weekly-report");
        assertThat(Skill.fromMarkdown("x", "---\nname: Weekly-Report\n---\nBody", SkillSource.USER, null).name())
                .isEqualTo("weekly-report");
    }

    @Test
    void markdownRoundTrips() {
        Skill skill = Skill.create("weekly-report", "Use when writing the weekly report",
                "1. Read last week's.\n2. Write this week's.", List.of("file_read"));

        Skill read = Skill.fromMarkdown("other-name", skill.toMarkdown(), SkillSource.MANAGED, null);

        assertThat(read.name()).isEqualTo(skill.name());
        assertThat(read.description()).isEqualTo(skill.description());
        assertThat(read.instructions()).isEqualTo(skill.instructions());
        assertThat(read.tools()).isEqualTo(skill.tools());
    }

    @Test
    void thePromptLineIsTheNameAndWhenToUseIt() {
        assertThat(Skill.create("release", "Use when cutting a release", "…", List.of()).promptLine())
                .isEqualTo("- release: Use when cutting a release");
        assertThat(Skill.create("release", "", "…", List.of()).promptLine())
                .isEqualTo("- release");
    }

    @Test
    void instructionsLongerThanTheCapAreCutRatherThanDropped() {
        String long_ = "x".repeat(Skill.MAX_INSTRUCTION_CHARS + 500);
        Skill skill = Skill.create("big", "d", long_, List.of());

        String loaded = skill.loadedInstructions();

        assertThat(loaded).hasSizeGreaterThan(Skill.MAX_INSTRUCTION_CHARS)
                .startsWith("x")
                .contains("were cut here");
        assertThat(loaded.replaceAll("[^x]", "")).hasSize(Skill.MAX_INSTRUCTION_CHARS);
    }
}
