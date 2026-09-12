package ai.mindconnect.agent.registry;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrySourceTest {

    @Test
    void reads_the_short_form_and_fills_in_the_defaults() {
        RegistrySource source = RegistrySource.of("mindconnect-ai/registry");

        assertThat(source.owner()).isEqualTo("mindconnect-ai");
        assertThat(source.repo()).isEqualTo("registry");
        assertThat(source.ref()).isEqualTo(RegistrySource.DEFAULT_REF);
        assertThat(source.indexPath()).isEqualTo(RegistrySource.DEFAULT_INDEX_PATH);
        assertThat(source.baseUrl()).isEqualTo(RegistrySource.GITHUB_RAW_BASE_URL);
        assertThat(source.enabled()).isTrue();
        assertThat(source.id().value()).isEqualTo("mindconnect-ai-registry");
    }

    @Test
    void reads_a_pinned_tag_and_a_custom_index_path() {
        RegistrySource source = RegistrySource.of("acme/agents@v1.2.0:catalog/index.json");

        assertThat(source.ref()).isEqualTo("v1.2.0");
        assertThat(source.indexPath()).isEqualTo("catalog/index.json");
        assertThat(source.coordinates()).isEqualTo("acme/agents@v1.2.0");
    }

    @Test
    void reads_the_url_from_the_browsers_address_bar() {
        assertThat(RegistrySource.of("https://github.com/acme/agents.git").repo()).isEqualTo("agents");
        assertThat(RegistrySource.of("https://github.com/acme/agents").owner()).isEqualTo("acme");
    }

    @Test
    void refuses_text_that_names_no_repository() {
        assertThatThrownBy(() -> RegistrySource.of("agents"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegistrySource.of(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_same_repository_always_gets_the_same_id_so_adding_it_twice_collides() {
        assertThat(RegistrySource.of("Acme/Agents").id())
                .isEqualTo(RegistrySource.of("acme/agents").id());
    }

    @Test
    void a_github_source_names_its_page_and_another_host_names_none() {
        assertThat(RegistrySource.of("acme/agents").repositoryUrl())
                .isEqualTo("https://github.com/acme/agents");

        RegistrySource enterprise = new RegistrySource(RegistrySource.of("acme/agents").id(), null,
                "acme", "agents", null, null, null, "https://raw.github.acme.internal", true, null);

        // Its web address does not follow from its raw-content one, and a link
        // to github.com/acme/agents would point at somebody else's repository.
        assertThat(enterprise.repositoryUrl()).isNull();
    }

    @Test
    void a_blank_token_variable_is_no_token_variable() {
        RegistrySource source = new RegistrySource(
                RegistrySource.of("acme/agents").id(), null, "acme", "agents", null, null,
                "  ", null, true, null);

        assertThat(source.tokenEnvVar()).isNull();
        assertThat(source.name()).isEqualTo("acme/agents");
    }
}
