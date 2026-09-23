package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A chat keeps its own know-how: written, listed by group, read back, taken away. */
class SkillToolProviderTest {

    private final SkillRepository store = new MemoryStore();
    private final SkillToolProvider provider = bound(store);

    @Test
    void a_skill_written_in_a_chat_is_a_stored_managed_skill_with_its_group() {
        String answer = tool(SkillToolProvider.SAVE).execute(Map.of(
                "name", "Mail-Rules", "group", "office",
                "description", "Use before clearing out the inbox",
                "instructions", "## Never delete\n- invoices\n- Katrin"));

        assertThat(answer).startsWith("Created skill \"mail-rules\" [office]");
        Skill stored = store.findByName("mail-rules").orElseThrow();
        assertThat(stored.group()).isEqualTo("office");
        assertThat(stored.source()).isEqualTo(SkillSource.MANAGED);
        assertThat(stored.enabled()).isTrue();
        assertThat(stored.toMarkdown()).contains("group: office").contains("- Katrin");
    }

    @Test
    void saving_again_changes_only_what_is_passed_and_keeps_the_group() {
        tool(SkillToolProvider.SAVE).execute(Map.of("name", "mail-rules", "group", "office",
                "description", "before a cleanup", "instructions", "one"));
        String answer = tool(SkillToolProvider.SAVE).execute(Map.of("name", "mail-rules", "instructions", "one\ntwo"));

        assertThat(answer).startsWith("Updated skill \"mail-rules\" [office]");
        Skill stored = store.findByName("mail-rules").orElseThrow();
        assertThat(stored.instructions()).isEqualTo("one\ntwo");
        assertThat(stored.description()).isEqualTo("before a cleanup");
        assertThat(stored.group()).isEqualTo("office");
    }

    @Test
    void the_list_is_by_group_and_the_group_filter_narrows_it() {
        tool(SkillToolProvider.SAVE).execute(Map.of("name", "release", "instructions", "tag, push"));
        tool(SkillToolProvider.SAVE).execute(Map.of("name", "mail-rules", "group", "office", "instructions", "x"));

        assertThat(tool(SkillToolProvider.LIST).execute(Map.of()))
                .isEqualTo("- release [general]\n- mail-rules [office]\n");
        assertThat(tool(SkillToolProvider.LIST).execute(Map.of("group", "office")))
                .isEqualTo("- mail-rules [office]\n");
        assertThat(tool(SkillToolProvider.GET).execute(Map.of("name", "mail-rules")))
                .startsWith("---\nname: mail-rules\ngroup: office\n");
    }

    @Test
    void a_skill_off_disk_is_neither_written_nor_deleted_here() {
        store.save(Skill.fromMarkdown("release", "---\nname: release\n---\nTag it.", SkillSource.USER, "/home/me/skills/release"));

        assertThatThrownBy(() -> tool(SkillToolProvider.SAVE).execute(Map.of("name", "release", "instructions", "no")))
                .hasMessageContaining("edited there, not here");
        assertThatThrownBy(() -> tool(SkillToolProvider.DELETE).execute(Map.of("name", "release")))
                .hasMessageContaining("deleted there, not here");
        assertThatThrownBy(() -> tool(SkillToolProvider.GET).execute(Map.of("name", "nope")))
                .hasMessageContaining("no stored skill named \"nope\"");
    }

    @Test
    void delete_takes_a_managed_skill_away_and_a_bad_name_is_refused() {
        tool(SkillToolProvider.SAVE).execute(Map.of("name", "mail-rules", "instructions", "x"));
        assertThat(tool(SkillToolProvider.DELETE).execute(Map.of("name", "mail-rules"))).isEqualTo("Deleted skill \"mail-rules\".");
        assertThat(store.findByName("mail-rules")).isEmpty();

        assertThatThrownBy(() -> tool(SkillToolProvider.SAVE).execute(Map.of("name", "My Rules", "instructions", "x")))
                .hasMessageContaining("not usable");
    }

    @Test
    void without_a_store_the_bundle_is_not_there() {
        SkillToolProvider none = new SkillToolProvider();
        none.bind(env(null));
        assertThat(none.isAvailable()).isFalse();
        assertThat(none.create(SkillToolProvider.LIST, null, null)).isEmpty();
        assertThat(provider.toolNames()).containsExactly("skills_list", "skills_get", "skills_save", "skills_delete");
        assertThat(provider.group()).isEqualTo("skills");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private Tool tool(String name) {
        return provider.create(name, null, null).orElseThrow();
    }

    private static SkillToolProvider bound(SkillRepository store) {
        SkillToolProvider provider = new SkillToolProvider();
        provider.bind(env(store));
        return provider;
    }

    private static ToolEnvironment env(SkillRepository store) {
        return new ToolEnvironment() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                return type == SkillRepository.class && store != null ? Optional.of((T) store) : Optional.empty();
            }
            @Override public Optional<String> getString(String key) { return Optional.empty(); }
        };
    }

    /** Enough of a store for the tools: by id, by name, in order of saving. */
    private static final class MemoryStore implements SkillRepository {
        private final List<Skill> skills = new ArrayList<>();

        @Override public List<Skill> findAll() { return List.copyOf(skills); }
        @Override public Optional<Skill> findById(SkillId id) { return skills.stream().filter(s -> s.id().equals(id)).findFirst(); }
        @Override public Optional<Skill> findByName(String name) { return skills.stream().filter(s -> s.name().equals(name)).findFirst(); }
        @Override public Skill save(Skill skill) {
            skills.removeIf(s -> s.id().equals(skill.id()));
            skills.add(skill);
            return skill;
        }
        @Override public void deleteById(SkillId id) { skills.removeIf(s -> s.id().equals(id)); }
    }
}
