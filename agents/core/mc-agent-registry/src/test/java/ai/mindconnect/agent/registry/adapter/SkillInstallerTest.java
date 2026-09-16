package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.registry.adapter.installer.SkillInstaller;
import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillInstallerTest {

    private static final String MARKDOWN = """
            ---
            name: weekly-report
            description: Use when writing the weekly status report
            tools: file_read, bash
            ---
            # Weekly report

            1. Read last week's report.
            2. Write this week's.
            """;

    private static final RegistryEntry ENTRY = new RegistryEntry("weekly-report",
            RegistryItemType.SKILL, "weekly-report", null, "1.0", "skills/weekly-report/SKILL.md",
            List.of(), null, null, List.of());

    private FakeRepository repository;
    private SkillInstaller installer;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        installer = new SkillInstaller(repository);
    }

    @Test
    void installs_the_skill_as_a_managed_one_with_its_front_matter() throws Exception {
        ImportedItem item = installer.install(ENTRY, MARKDOWN, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(item.type()).isEqualTo(RegistryItemType.SKILL);
        Skill saved = repository.findByName("weekly-report").orElseThrow();
        assertThat(saved.description()).isEqualTo("Use when writing the weekly status report");
        assertThat(saved.tools()).containsExactly("file_read", "bash");
        assertThat(saved.instructions()).startsWith("# Weekly report");
        assertThat(saved.source()).isEqualTo(SkillSource.MANAGED);
        assertThat(saved.directory()).isNull();
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.createdAt()).isNotNull();
    }

    @Test
    void the_entry_name_stands_in_when_the_front_matter_names_none() throws Exception {
        String markdown = """
                ---
                description: No name up here
                ---
                Do the thing.
                """;

        installer.install(ENTRY, markdown, ImportMode.SKIP_EXISTING);

        assertThat(repository.findByName("weekly-report")).isPresent();
    }

    @Test
    void a_file_without_instructions_is_not_a_skill() {
        assertThatThrownBy(() -> installer.install(ENTRY, "---\nname: empty\n---\n", ImportMode.OVERWRITE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a skill");
    }

    @Test
    void skips_an_existing_skill_unless_told_to_overwrite() throws Exception {
        repository.save(Skill.create("weekly-report", "old", "old instructions", List.of()));

        ImportedItem item = installer.install(ENTRY, MARKDOWN, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.SKIPPED);
        assertThat(repository.findByName("weekly-report").orElseThrow().instructions())
                .isEqualTo("old instructions");
    }

    @Test
    void an_overwrite_keeps_the_local_id_and_the_enabled_flag() throws Exception {
        Skill existing = Skill.create("weekly-report", "old", "old instructions", List.of())
                .withFields("weekly-report", "old", "old instructions", List.of(), false);
        repository.save(existing);

        ImportedItem item = installer.install(ENTRY, MARKDOWN, ImportMode.OVERWRITE);

        assertThat(item.status()).isEqualTo(ImportStatus.UPDATED);
        Skill saved = repository.findByName("weekly-report").orElseThrow();
        assertThat(saved.id()).isEqualTo(existing.id());
        assertThat(saved.createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.instructions()).startsWith("# Weekly report");
        assertThat(saved.enabled()).isFalse();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void exists_asks_by_name() {
        assertThat(installer.exists("weekly-report")).isFalse();
        repository.save(Skill.create("weekly-report", "d", "i", List.of()));
        assertThat(installer.exists("weekly-report")).isTrue();
    }

    @Test
    void remove_deletes_the_skill_of_the_entry_name() {
        repository.save(Skill.create("weekly-report", "d", "i", List.of()));

        ImportedItem removed = installer.remove(ENTRY);
        ImportedItem again = installer.remove(ENTRY);

        assertThat(removed.status()).isEqualTo(ImportStatus.REMOVED);
        assertThat(repository.findAll()).isEmpty();
        assertThat(again.status()).isEqualTo(ImportStatus.SKIPPED);
    }

    private static class FakeRepository implements SkillRepository {

        private final List<Skill> skills = new ArrayList<>();

        @Override
        public List<Skill> findAll() {
            return List.copyOf(skills);
        }

        @Override
        public Optional<Skill> findById(SkillId id) {
            return skills.stream().filter(s -> s.id().equals(id)).findFirst();
        }

        @Override
        public Optional<Skill> findByName(String name) {
            return skills.stream().filter(s -> s.name().equals(name)).findFirst();
        }

        @Override
        public Skill save(Skill skill) {
            skills.removeIf(s -> s.id().equals(skill.id()));
            skills.add(skill);
            return skill;
        }

        @Override
        public void deleteById(SkillId id) {
            skills.removeIf(s -> s.id().equals(id));
        }
    }
}
