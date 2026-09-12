package ai.mindconnect.agent.registry.adapter.github;

import ai.mindconnect.agent.registry.domain.RegistryException;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL building, which is the whole of the client that can be tested without a
 * network — and the one part that must not be sloppy: the paths come out of
 * somebody else's index file.
 */
class GitHubRegistryClientTest {

    private final GitHubRegistryClient client =
            new GitHubRegistryClient(Duration.ZERO, Duration.ofSeconds(1), name -> null);

    private final RegistrySource source = RegistrySource.of("acme/registry@v1.0.0");

    @Test
    void builds_the_raw_url_of_a_repository_file() {
        assertThat(client.rawUrl(source, "agents/web-researcher.json"))
                .isEqualTo("https://raw.githubusercontent.com/acme/registry/v1.0.0/"
                        + "agents/web-researcher.json");
    }

    @Test
    void a_leading_slash_is_not_a_second_repository_root() {
        assertThat(client.rawUrl(source, "/registry.json"))
                .isEqualTo(client.rawUrl(source, "registry.json"));
    }

    @Test
    void refuses_a_path_that_climbs_out_of_the_repository() {
        assertThatThrownBy(() -> client.rawUrl(source, "../../etc/passwd"))
                .isInstanceOf(RegistryException.class);
        assertThatThrownBy(() -> client.rawUrl(source, "https://evil.example.com/x.json"))
                .isInstanceOf(RegistryException.class);
    }

    @Test
    void encodes_each_segment_but_keeps_the_separators() {
        assertThat(client.rawUrl(source, "agents/my agent.json"))
                .endsWith("/agents/my%20agent.json");
    }

    @Test
    void an_enterprise_host_replaces_the_raw_base_url() {
        RegistrySource enterprise = new RegistrySource(source.id(), null, "acme", "registry",
                "main", null, null, "https://raw.github.acme.internal/", true, null);

        assertThat(client.rawUrl(enterprise, "registry.json"))
                .isEqualTo("https://raw.github.acme.internal/acme/registry/main/registry.json");
    }
}
