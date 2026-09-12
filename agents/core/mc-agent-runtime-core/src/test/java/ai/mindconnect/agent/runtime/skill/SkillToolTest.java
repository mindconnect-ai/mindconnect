package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code skill} tool: the names in the prompt, the body only on demand.
 */
class SkillToolTest {

    private static final class Store implements SkillRepository {
        private final List<Skill> skills = new ArrayList<>();
        @Override public List<Skill> findAll() { return List.copyOf(skills); }
        @Override public Optional<Skill> findById(SkillId id) { return Optional.empty(); }
        @Override public Optional<Skill> findByName(String name) { return Optional.empty(); }
        @Override public Skill save(Skill skill) { skills.add(skill); return skill; }
        @Override public void deleteById(SkillId id) { }
    }

    private static SkillTool tool(List<String> names) {
        Store store = new Store();
        store.save(Skill.create("release", "Use when cutting a release",
                "1. Tag the commit.\n2. Push the tag.", List.of("bash")));
        store.save(Skill.create("house-style", "Use when writing for customers",
                "Short sentences.", List.of()));
        return new SkillTool(SkillCatalog.of(store), names, UserId.of("alice"), null);
    }

    @Test
    void theDescriptionAndTheSchemaCarryTheNames() {
        SkillTool tool = tool(List.of());

        assertThat(tool.name()).isEqualTo("skill");
        assertThat(tool.description())
                .contains("release — Use when cutting a release")
                .contains("house-style — Use when writing for customers");
        assertThat(tool.parametersSchema())
                .extracting(schema -> ((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("name")).get("enum"))
                .as("a model cannot invent a name it is given a list of")
                .isEqualTo(List.of("house-style", "release"));
    }

    @Test
    void loadingASkillHandsOverTheInstructions() {
        String result = tool(List.of()).execute(Map.of("name", "release"));

        assertThat(result)
                .startsWith("# Skill: release")
                .contains("Use when cutting a release")
                .contains("Tools it expects: bash")
                .contains("grants you nothing")
                .contains("1. Tag the commit.")
                .contains("2. Push the tag.");
    }

    @Test
    void aSkillOffDiskIsHandedOverWithItsDirectory() {
        Store store = new Store();
        Skill fromDisk = Skill.fromMarkdown("weekly-report",
                "---\ndescription: d\n---\nStart from template.md.",
                SkillSource.PROJECT, "/repo/.mindconnect/skills/weekly-report");
        store.save(fromDisk);
        var tool = new SkillTool(SkillCatalog.of(store), List.of(), null, null);

        assertThat(tool.execute(Map.of("name", "weekly-report")))
                .contains("Directory: `/repo/.mindconnect/skills/weekly-report`")
                .contains("open them by path with the file tools");
    }

    @Test
    void aNameTheAgentDoesNotHaveIsRefusedWithWhatItDoesHave() {
        SkillTool tool = tool(List.of("release"));

        assertThat(tool.execute(Map.of("name", "house-style")))
                .as("a skill the agent was not given is not its to load")
                .isEqualTo("No skill named 'house-style'. Available: release.");
        assertThat(tool.execute(Map.of("name", "nonsense")))
                .startsWith("No skill named 'nonsense'.");
        assertThat(tool.execute(Map.of())).startsWith("Error: 'name' must be the name of a skill.");
    }

    @Test
    void theNameIsMatchedAsTheModelTypesIt() {
        assertThat(tool(List.of()).execute(Map.of("name", "  Release "))).startsWith("# Skill: release");
    }

    @Test
    void anAgentWithoutAnySkillSaysSoRatherThanFailing() {
        var tool = new SkillTool(SkillCatalog.none(), List.of(), null, null);

        assertThat(tool.parametersSchema())
                .extracting(schema -> ((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("name")).get("enum"))
                .isNull();
        assertThat(tool.execute(Map.of("name", "release")))
                .isEqualTo("No skill named 'release'. This agent has no skills.");
    }
}
