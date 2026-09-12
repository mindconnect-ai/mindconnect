package ai.mindconnect.agent.registry;

import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryIndexTest {

    private static RegistryEntry entry(String id, RegistryItemType type, String name, String... tags) {
        return new RegistryEntry(id, type, name, "Does " + name, "1.0.0",
                type.wireName() + "s/" + id + ".json", List.of(tags), "someone", null, List.of());
    }

    private final RegistryIndex index = new RegistryIndex(1, "Test registry", null, List.of(
            entry("web-researcher", RegistryItemType.AGENT, "web-researcher", "research"),
            entry("default-llm", RegistryItemType.LLM_CONFIG, "default"),
            entry("summarize", RegistryItemType.WORKFLOW, "summarize", "text")));

    @Test
    void finds_an_entry_by_id() {
        assertThat(index.find("summarize")).isPresent();
        assertThat(index.find("nope")).isEmpty();
        assertThat(index.find(null)).isEmpty();
    }

    @Test
    void a_blank_query_matches_everything_in_index_order() {
        assertThat(index.search(null, null)).hasSize(3);
        assertThat(index.search("  ", null)).extracting(RegistryEntry::id)
                .containsExactly("web-researcher", "default-llm", "summarize");
    }

    @Test
    void searches_name_description_and_tags_ignoring_case() {
        assertThat(index.search("RESEARCH", null)).extracting(RegistryEntry::id)
                .containsExactly("web-researcher");
        assertThat(index.search("does summarize", null)).extracting(RegistryEntry::id)
                .containsExactly("summarize");
    }

    @Test
    void filters_by_kind() {
        assertThat(index.search(null, RegistryItemType.AGENT)).extracting(RegistryEntry::id)
                .containsExactly("web-researcher");
        assertThat(index.search("x", RegistryItemType.AGENT)).isEmpty();
    }

    @Test
    void an_entry_without_a_path_is_no_entry() {
        assertThatThrownBy(() -> new RegistryEntry("a", RegistryItemType.AGENT, "a", null, null,
                null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_type_reads_in_whatever_spelling_the_index_author_used() {
        assertThat(RegistryItemType.fromWire("llm-config")).isEqualTo(RegistryItemType.LLM_CONFIG);
        assertThat(RegistryItemType.fromWire("LLM_CONFIG")).isEqualTo(RegistryItemType.LLM_CONFIG);
        assertThat(RegistryItemType.fromWire(" Agent ")).isEqualTo(RegistryItemType.AGENT);
        assertThatThrownBy(() -> RegistryItemType.fromWire("prompt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
