package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Administering the registrations of the in-process gateway.
 *
 * <p>Writes go to the repository; whatever the gateway had discovered for
 * that server is dropped in the same breath, so a saved change is visible on
 * the next lookup instead of at the next restart.
 */
public final class LocalMcpRegistryAdmin implements McpRegistryAdmin {

    private static final Logger log = LoggerFactory.getLogger(LocalMcpRegistryAdmin.class);

    private final McpServerRepository repository;
    private final LocalMcpGateway gateway;

    public LocalMcpRegistryAdmin(McpServerRepository repository, LocalMcpGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    @Override
    public List<McpServerRegistration> all() {
        return repository.findAll();
    }

    @Override
    public Optional<McpServerRegistration> findById(McpServerId id) {
        return repository.findById(id);
    }

    @Override
    public void save(McpServerRegistration registration) {
        McpServerRegistration stamped = new McpServerRegistration(
                registration.id(),
                registration.displayName(),
                registration.description(),
                registration.enabled(),
                registration.toolNamePrefix(),
                registration.target(),
                Instant.now());
        repository.save(stamped);
        gateway.forget(stamped.id());
    }

    @Override
    public void delete(McpServerId id) {
        repository.deleteById(id);
        gateway.forget(id);
    }

    @Override
    public McpDiscovery discovery(McpServerId id) {
        return gateway.remembered(id);
    }

    @Override
    public void refresh(McpServerId id) {
        gateway.forget(id);
        log.info("MCP server '{}': discovery dropped, next lookup asks again", id);
    }

    @Override
    public McpProbeResult probe(McpServerRegistration draft) {
        long started = System.currentTimeMillis();
        try {
            List<McpTool> tools = gateway.probeTools(draft);
            return McpProbeResult.success(tools, System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            log.info("probe of MCP server '{}' failed: {}", draft.id(), e.toString());
            return McpProbeResult.failure(rootMessage(e), System.currentTimeMillis() - started);
        }
    }

    /**
     * The message an operator can act on.
     *
     * <p>Usually the innermost one: a failed container start arrives wrapped
     * several layers deep, and the outer layers say nothing. But an
     * {@link McpGatewayException} exists precisely to carry a sentence written
     * for this screen — "header 'Authorization' cannot be resolved: …" names
     * the field as well as the variable — so one of those wins over the
     * library exception underneath it.
     *
     * <p>Some roots say nothing at all: an unknown host ends in an
     * {@code UnresolvedAddressException} without a message, and its bare class
     * name told nobody which url failed, and neither did the SDK's "Client failed
     * to initialize" around it. Then the outermost layer that says something —
     * the proxy's "MCP initialize failed for endpoint …", which carries the
     * layers below in its text — speaks, with the root's name after it.
     */
    static String rootMessage(Throwable e) {
        if (e instanceof McpGatewayException && hasMessage(e)) {
            return e.getMessage();
        }
        Throwable root = e;
        Throwable spoken = hasMessage(e) ? e : null;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
            if (spoken == null && hasMessage(root)) spoken = root;
        }
        if (hasMessage(root)) return root.getMessage();
        if (spoken == null) return root.toString();
        String name = root.getClass().getSimpleName();
        return spoken.getMessage().contains(name) ? spoken.getMessage() : spoken.getMessage() + " (" + name + ")";
    }

    /** A message of its own — not the one {@code new Exception(cause)} copies from its cause. */
    private static boolean hasMessage(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isBlank()
                && (e.getCause() == null || !message.equals(e.getCause().toString()));
    }
}
