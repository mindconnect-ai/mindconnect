package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;

import java.util.List;
import java.util.Optional;

/**
 * Where registrations are kept. An implementation detail of the local
 * gateway on purpose: callers reach registrations through
 * {@link ai.mindconnect.mcp.gateway.McpGateway}, so a runtime that talks to
 * a gateway server never sees a repository it does not own. Like every
 * store, bound to the one namespace its process serves.
 */
public interface McpServerRepository {

    Optional<McpServerRegistration> findById(McpServerId id);

    /** All registrations, enabled or not, in a stable order. */
    List<McpServerRegistration> findAll();

    /**
     * The registration a person would call {@code name} — its display name,
     * ignoring case. Ids are what code holds on to; names are what people type.
     */
    Optional<McpServerRegistration> findByName(String name);

    /** Creates or replaces a registration. */
    void save(McpServerRegistration registration);

    /** Removes a registration; a missing one is not an error. */
    void deleteById(McpServerId id);

    /**
     * Changes with every modification. The gateway hangs its discovery cache
     * off this, so a changed registration takes effect without a restart.
     */
    long version();
}
