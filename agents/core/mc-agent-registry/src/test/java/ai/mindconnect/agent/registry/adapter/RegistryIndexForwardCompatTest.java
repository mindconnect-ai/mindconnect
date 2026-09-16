package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An index written for a newer Mindconnect — one that knows a kind of entry
 * this version does not — must still read here, minus those entries.
 */
class RegistryIndexForwardCompatTest {

    private static final String INDEX = """
            {
              "schemaVersion": 1,
              "name": "Ahead of us",
              "entries": [
                {"id": "a", "type": "agent", "name": "a", "path": "agents/a.json"},
                {"id": "h", "type": "hologram", "name": "h", "path": "holograms/h.json"},
                {"id": "s", "type": "skill", "name": "s", "path": "skills/s/SKILL.md"},
                {"id": "p", "type": "prompt", "name": "p", "path": "prompts/p.md"}
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void entries_of_an_unknown_kind_are_left_out_and_counted() throws Exception {
        RegistryIndex index = mapper.readValue(INDEX, RegistryIndex.class);

        assertThat(index.entries()).extracting(RegistryEntry::id).containsExactly("a", "s");
        assertThat(index.entries()).extracting(RegistryEntry::type)
                .containsExactly(RegistryItemType.AGENT, RegistryItemType.SKILL);
        assertThat(index.unknownEntries()).isEqualTo(2);
    }

    @Test
    void an_entry_without_a_type_is_still_an_error() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"id\": \"x\", \"name\": \"x\", \"path\": \"x.json\"}", RegistryEntry.class))
                .hasMessageContaining("has no type");
    }

    @Test
    void an_index_every_entry_of_which_reads_counts_nothing() throws Exception {
        RegistryIndex index = mapper.readValue(
                "{\"entries\": [{\"id\": \"a\", \"type\": \"agent\", \"name\": \"a\", \"path\": \"a.json\"}]}",
                RegistryIndex.class);

        assertThat(index.entries()).hasSize(1);
        assertThat(index.unknownEntries()).isZero();
    }
}
