package ai.mindconnect.mcp.gateway;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One registered MCP server: what it is called, how it is reached, and under
 * which prefix its tools appear.
 *
 * <p>No namespace here: like every domain object, a registration belongs to
 * the namespace of the store that holds it. There is no owner either:
 * registering is an operator's job, and who gets to <em>see</em> which tools
 * becomes a filter over this set later, not a second set.
 *
 * <p>Credentials are part of the target — an environment value, ideally a
 * {@code ${VAR}} reference resolved when the server starts, or a mounted
 * directory. A reference to a credential store (§8) replaces that once the
 * store exists.
 *
 * @param id              stable identifier
 * @param displayName     name for humans; falls back to the id when blank
 * @param description     what this server is for; may be null
 * @param enabled         false hides it from callers without deleting it
 * @param toolNamePrefix  head of its sub-tools' names — {@code "gmail"} makes
 *                        the server's {@code search_emails} appear as
 *                        {@code gmail_search_emails}; fixed once registered
 * @param target          how to reach the server
 * @param updatedAt       last change; may be null for a seeded record
 */
/*
 * Checked here rather than in the UI because every path to a registration
 * passes through this constructor — the form, a hand-dropped file, a seeded
 * one. A bad prefix does not cost this tool: the name reaches the model
 * provider inside a request carrying every other tool too, and the provider
 * answers 400 for all of them.
 */
public record McpServerRegistration(
        McpServerId id,
        String displayName,
        String description,
        boolean enabled,
        String toolNamePrefix,
        McpTarget target,
        Instant updatedAt
) {

    public McpServerRegistration {
        if (id == null) {
            throw new IllegalArgumentException("id required");
        }
        if (toolNamePrefix == null || toolNamePrefix.isBlank()) {
            throw new IllegalArgumentException("toolNamePrefix required for server '" + id + "'");
        }
        if (!TOOL_NAME_PREFIX.matcher(toolNamePrefix).matches()) {
            throw new IllegalArgumentException(
                    "toolNamePrefix must be 1-32 characters of letters, digits, '_' or '-' — "
                            + "it becomes part of every tool name this server offers, and a model "
                            + "provider rejects the whole request over one bad name: " + toolNamePrefix);
        }
        if (target == null) {
            throw new IllegalArgumentException("target required for server '" + id + "'");
        }
        displayName = displayName == null || displayName.isBlank() ? id.value() : displayName;
    }

    /**
     * What an agent tool name may consist of. Both OpenAI and Anthropic
     * require {@code ^[a-zA-Z0-9_-]{1,64}$}; 32 here leaves room for the
     * sub-tool name the prefix is joined to.
     */
    private static final Pattern TOOL_NAME_PREFIX = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public McpServerInfo toInfo() {
        return new McpServerInfo(id, displayName, description, toolNamePrefix);
    }
}
