package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an agent may load, out of the three places skills come from, and what
 * its prompt says about it.
 */
class SkillCatalogTest {

    @TempDir
    Path userDir;

    @TempDir
    Path project;

    /** A store that holds what a test puts in it. */
    private static final class Store implements SkillRepository {
        private final List<Skill> skills = new ArrayList<>();
        @Override public List<Skill> findAll() { return List.copyOf(skills); }
        @Override public Optional<Skill> findById(SkillId id) {
            return skills.stream().filter(s -> s.id().equals(id)).findFirst();
        }
        @Override public Optional<Skill> findByName(String name) {
            return skills.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
        }
        @Override public Skill save(Skill skill) { skills.add(skill); return skill; }
        @Override public void deleteById(SkillId id) { skills.removeIf(s -> s.id().equals(id)); }
    }

    private AgentSession session() {
        return AgentSession.start(AgentId.random(), UserId.of("alice"), ConversationId.random())
                .withWorkingDir(project.toString());
    }

    private static AgentDefinition agent(AgentDefinition.SkillsConfig skills) {
        return AgentDefinition.create("main", "d", "p", null, "gpt").withSkills(skills);
    }

    @Test
    void theThreeSourcesAddUpAndTheMoreSpecificOneWins() throws Exception {
        Store store = new Store();
        store.save(Skill.create("house-style", "the installation's", "Installation says so.", List.of()));
        store.save(Skill.create("release", "stored", "Cut a release.", List.of()));
        Files.writeString(userDir.resolve("house-style.md"), "---\ndescription: the user's\n---\nUser says so.");
        Files.writeString(userDir.resolve("scratch.md"), "Notes.");
        Path projectSkills = Files.createDirectories(project.resolve(FileSkills.PROJECT_DIR));
        Files.writeString(projectSkills.resolve("house-style.md"),
                "---\ndescription: the project's\n---\nProject says so.");

        var catalog = SkillCatalog.of(store, userDir.toString());
        var all = catalog.all(UserId.of("alice"), project.toString());

        assertThat(all).extracting(Skill::name)
                .containsExactly("house-style", "release", "scratch");
        assertThat(all.get(0).description())
                .as("the project is right about its own work")
                .isEqualTo("the project's");
        assertThat(all.get(0).source()).isEqualTo(SkillSource.PROJECT);
    }

    @Test
    void anAgentSeesOnlyTheSkillsItNames() {
        Store store = new Store();
        store.save(Skill.create("release", "d", "i", List.of()));
        store.save(Skill.create("house-style", "d", "i", List.of()));
        var catalog = SkillCatalog.of(store);

        assertThat(catalog.available(agent(new AgentDefinition.SkillsConfig(true, List.of("release"))), session()))
                .extracting(Skill::name).containsExactly("release");
        assertThat(catalog.available(agent(AgentDefinition.SkillsConfig.all()), session()))
                .as("naming none means every skill there is")
                .extracting(Skill::name).containsExactly("house-style", "release");
        assertThat(catalog.available(agent(AgentDefinition.SkillsConfig.OFF), session())).isEmpty();
        assertThat(catalog.available(agent(null), session()))
                .as("an agent saved before the field existed has skills off")
                .isEmpty();
    }

    @Test
    void aSkillSwitchedOffIsOfferedToNobody() {
        Store store = new Store();
        store.save(Skill.create("release", "d", "i", List.of())
                .withFields("release", "d", "i", List.of(), false));
        var catalog = SkillCatalog.of(store);

        assertThat(catalog.all(null, null)).isEmpty();
        assertThat(catalog.find(agent(AgentDefinition.SkillsConfig.all()), session(), "release")).isEmpty();
    }

    @Test
    void thePromptSectionListsNamesAndDescriptionsAndNothingElse() {
        Store store = new Store();
        store.save(Skill.create("release", "Use when cutting a release",
                "SECRET-INSTRUCTION-BODY", List.of()));
        var catalog = SkillCatalog.of(store);

        String section = catalog.promptSection(agent(AgentDefinition.SkillsConfig.all()), session());

        assertThat(section)
                .startsWith("\n\n## Skills\n")
                .contains("call the `skill` tool")
                .contains("- release: Use when cutting a release")
                .as("the body is what the skill tool is for")
                .doesNotContain("SECRET-INSTRUCTION-BODY");
    }

    @Test
    void anAgentWithoutSkillsGetsNoSection() {
        Store store = new Store();
        store.save(Skill.create("release", "d", "i", List.of()));

        assertThat(SkillCatalog.of(store).promptSection(agent(AgentDefinition.SkillsConfig.OFF), session()))
                .isEmpty();
        assertThat(SkillCatalog.none().promptSection(agent(AgentDefinition.SkillsConfig.all()), session()))
                .isEmpty();
    }

    @Test
    void aPerUserDirectoryGivesEveryUserTheirOwn() throws Exception {
        Path alice = Files.createDirectories(userDir.resolve("alice"));
        Files.writeString(alice.resolve("mine.md"), "Alice's way.");
        var catalog = SkillCatalog.of(null, userDir + "/{user}");

        assertThat(catalog.all(UserId.of("alice"), null)).extracting(Skill::name).containsExactly("mine");
        assertThat(catalog.all(UserId.of("bob"), null)).isEmpty();
        assertThat(catalog.all(null, null)).as("no user, no user directory").isEmpty();
    }

    @Test
    void theUserScopeCanBeSwitchedOff() throws Exception {
        Files.writeString(userDir.resolve("mine.md"), "Not read.");

        assertThat(SkillCatalog.of(null, SkillCatalog.OFF).all(UserId.of("alice"), null)).isEmpty();
    }
}
