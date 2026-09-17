package ai.mindconnect.mcp.gateway.local;

import org.junit.jupiter.api.Test;

import java.nio.channels.UnresolvedAddressException;

import static org.assertj.core.api.Assertions.assertThat;

/** What the Test connection button shows when a probe fails. */
class ProbeMessageTest {

    @Test
    void aRootWithoutAMessageLetsTheLayerThatNamesTheEndpointSpeak() {
        var sdk = new RuntimeException("Client failed to initialize by explicit API call",
                new RuntimeException(new UnresolvedAddressException()));
        var failure = new IllegalStateException(
                "MCP initialize failed for endpoint https://mcp.example.com: " + sdk.getMessage(), sdk);

        assertThat(LocalMcpRegistryAdmin.rootMessage(failure))
                .isEqualTo("MCP initialize failed for endpoint https://mcp.example.com: Client failed to initialize by explicit API call (UnresolvedAddressException)");
    }

    @Test
    void theInnermostMessageStillWinsWhenTheRootHasOne() {
        var failure = new RuntimeException("wrapper", new RuntimeException("docker: image not found"));

        assertThat(LocalMcpRegistryAdmin.rootMessage(failure)).isEqualTo("docker: image not found");
    }

    @Test
    void nothingSaidAnywhereFallsBackToTheRootItself() {
        var failure = new RuntimeException((String) null, new UnresolvedAddressException());

        assertThat(LocalMcpRegistryAdmin.rootMessage(failure)).isEqualTo("java.nio.channels.UnresolvedAddressException");
    }
}
