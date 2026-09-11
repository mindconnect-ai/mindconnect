package ai.mindconnect.mcp.gateway.local;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A catalog that cannot be reached costs its suggestions. It must not cost
 * the thread it was asked on.
 */
class DockerMcpCatalogTest {

    @Test
    void an_unreachable_catalog_leaves_the_thread_alone() {
        // The point of this test: it did not. interrupt() sat inside a
        // blanket catch, so an IOException — an offline host, a proxy, a
        // refused connection — set the interrupt flag on the request thread.
        // The thread went back into the pool that way and the next blocking
        // call on it, for some unrelated request, died of it.
        DockerMcpCatalog catalog = new DockerMcpCatalog(
                "http://127.0.0.1:1/mcp/catalog.yaml",     // nothing listens on port 1
                Duration.ofHours(6), Duration.ofMillis(500));

        assertThat(catalog.search("github", 10)).isEmpty();
        assertThat(Thread.currentThread().isInterrupted())
                .as("interrupt flag after a failed catalog fetch")
                .isFalse();
    }

    @Test
    void a_required_secret_arrives_as_its_own_placeholder_not_as_an_empty_value() throws Exception {
        // Safe by default: the path of least resistance puts the secret in the
        // environment instead of into the registration file, which is stored
        // in clear. Whoever would rather paste the value overwrites it.
        var entries = DockerMcpCatalog.parse("""
                registry:
                  github-official:
                    title: GitHub Official
                    description: Talks to GitHub
                    image: mcp/github:latest
                    secrets:
                      - env: GITHUB_PERSONAL_ACCESS_TOKEN
                        description: a token with repo scope
                    tools:
                      - name: get_issue
                      - name: create_pull_request
                """);

        assertThat(entries).hasSize(1);
        var entry = entries.get(0);
        assertThat(entry.id()).isEqualTo("github-official");
        assertThat(entry.toolNames()).containsExactly("get_issue", "create_pull_request");
        assertThat(((ai.mindconnect.mcp.gateway.McpTarget.Docker) entry.target()).env())
                .containsEntry("GITHUB_PERSONAL_ACCESS_TOKEN", "${GITHUB_PERSONAL_ACCESS_TOKEN}");
        assertThat(entry.requiredEnv()).singleElement()
                .satisfies(required -> assertThat(required.secret()).isTrue());
    }

    @Test
    void an_entry_without_an_image_is_not_offered() throws Exception {
        // Remote servers are in the document too, and this catalog can only
        // describe what it could actually run here.
        assertThat(DockerMcpCatalog.parse("""
                registry:
                  remote-only:
                    title: Somebody else runs this
                """)).isEmpty();
    }

    @Test
    void a_failure_is_not_a_crash() {
        DockerMcpCatalog catalog = new DockerMcpCatalog(
                "http://127.0.0.1:1/mcp/catalog.yaml", Duration.ofHours(6), Duration.ofMillis(500));

        assertThat(catalog.search(null, 10)).isEmpty();
        assertThat(catalog.name()).isEqualTo("Docker MCP Catalog");
    }
}
