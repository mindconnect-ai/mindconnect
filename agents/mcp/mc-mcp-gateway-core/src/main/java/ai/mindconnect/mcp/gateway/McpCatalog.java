package ai.mindconnect.mcp.gateway;

import java.util.List;

/**
 * A directory of MCP servers somebody else maintains — a place to look
 * before writing a registration by hand.
 *
 * <p>Strictly a source of suggestions. Nothing a catalog returns is
 * registered, started or trusted by being listed: an entry becomes real
 * only when an operator saves it through {@link McpRegistryAdmin}, and the
 * warnings of concept 21 §9.1 apply in full — a catalog makes running
 * somebody else's container a two-click affair, which is convenient and
 * exactly why the operator has to be the one clicking.
 */
public interface McpCatalog {

    /** Where these entries come from, for the UI to say so. */
    String name();

    /**
     * Entries matching {@code query} (name and description, case-insensitive;
     * blank returns the head of the catalog), at most {@code limit}.
     *
     * <p>Never throws for a catalog that is unreachable or malformed: an
     * empty list is a usable answer, an exception in a search field is not.
     */
    List<McpCatalogEntry> search(String query, int limit);
}
