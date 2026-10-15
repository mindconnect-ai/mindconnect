package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The form's half of the screen: what an operator types becomes a registry,
 * and an edit stays the same registry.
 */
class RegistrySourceDraftTest {

    private static Map<String, Object> form(String repository, Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        body.put("repository", repository);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            body.put(pairs[i].toString(), pairs[i + 1]);
        }
        return body;
    }

    @Test
    void a_typed_repository_becomes_a_source_with_the_defaults_filled_in() {
        RegistrySource source = RegistrySourceDraft.fromForm(form("acme/agents")).toSource(null);

        assertThat(source.owner()).isEqualTo("acme");
        assertThat(source.ref()).isEqualTo(RegistrySource.DEFAULT_REF);
        assertThat(source.indexPath()).isEqualTo(RegistrySource.DEFAULT_INDEX_PATH);
        assertThat(source.enabled()).isTrue();
    }

    @Test
    void a_ref_typed_in_its_own_field_wins_over_the_shorthands_default() {
        RegistrySource source = RegistrySourceDraft.fromForm(form("acme/agents", "ref", "v2"))
                .toSource(null);

        assertThat(source.ref()).isEqualTo("v2");
    }

    @Test
    void a_ref_in_the_shorthand_survives_an_empty_ref_field() {
        RegistrySource source = RegistrySourceDraft.fromForm(form("acme/agents@v9", "ref", " "))
                .toSource(null);

        assertThat(source.ref()).isEqualTo("v9");
    }

    @Test
    void an_edit_keeps_the_id_and_the_version_so_it_stays_the_same_registry() {
        RegistrySource existing = RegistrySource.of("acme/agents").withVersion(3L);

        RegistrySource edited = RegistrySourceDraft.fromForm(
                        form("acme/other-repo", "name", "Renamed"))
                .toSource(existing);

        assertThat(edited.id()).isEqualTo(existing.id());
        assertThat(edited.version()).isEqualTo(3L);
        assertThat(edited.repo()).isEqualTo("other-repo");
        assertThat(edited.name()).isEqualTo("Renamed");
    }

    @Test
    void the_enabled_checkbox_reads_both_shapes_a_form_posts() {
        assertThat(RegistrySourceDraft.fromForm(form("a/b", "enabled", false)).enabled()).isFalse();
        assertThat(RegistrySourceDraft.fromForm(form("a/b", "enabled", "false")).enabled()).isFalse();
        assertThat(RegistrySourceDraft.fromForm(form("a/b", "enabled", true)).enabled()).isTrue();
        assertThat(RegistrySourceDraft.fromForm(form("a/b")).enabled()).isTrue();
    }

    @Test
    void an_empty_repository_field_is_refused_rather_than_saved() {
        assertThatThrownBy(() -> RegistrySourceDraft.fromForm(form("")).toSource(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_existing_source_fills_the_form_back_in() {
        RegistrySource source = RegistrySource.of("acme/agents@v1:catalog/index.json");

        RegistrySourceDraft draft = RegistrySourceDraft.of(source);

        assertThat(draft.repository()).isEqualTo("acme/agents");
        assertThat(draft.ref()).isEqualTo("v1");
        assertThat(draft.indexPath()).isEqualTo("catalog/index.json");
    }
}
