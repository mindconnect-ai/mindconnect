package ai.mindconnect.mcp.gateway;

import java.util.List;
import java.util.Optional;

/**
 * Managing registrations — the operator's side of the gateway, separate
 * from {@link McpGateway} because it has different callers, different
 * rights and a different cost profile: agents read the catalog on every
 * tool lookup, an operator writes it now and then (concept 21 §5.3).
 *
 * <p>Who may do this is not decided here — it is an operator's job, and the
 * check belongs on the {@code AccessGuard} (concept 21 §9).
 */
public interface McpRegistryAdmin {

    /** Every registration, enabled or not, in a stable order. */
    List<McpServerRegistration> all();

    Optional<McpServerRegistration> findById(McpServerId id);

    /**
     * Creates or replaces a registration and drops whatever was discovered
     * for it, so the next lookup asks the changed server again.
     */
    void save(McpServerRegistration registration);

    /** Removes a registration and its discovered tools. */
    void delete(McpServerId id);

    /**
     * Tries a registration out without saving it: start the server, do the
     * handshake, list the tools, shut it down again. This is the one
     * operation that deliberately ignores every cache — the point is to
     * find out what is true right now.
     */
    McpProbeResult probe(McpServerRegistration draft);

    /**
     * What is known about a saved server's tools, and since when — read from
     * what was remembered, without contacting anything.
     */
    McpDiscovery discovery(McpServerId id);

    /**
     * Forgets what was discovered for a server, so the next lookup asks it
     * again. For the case the registration did not change but the server
     * did — a new image tag behind the same name, a server that has learned
     * a tool.
     */
    void refresh(McpServerId id);
}
