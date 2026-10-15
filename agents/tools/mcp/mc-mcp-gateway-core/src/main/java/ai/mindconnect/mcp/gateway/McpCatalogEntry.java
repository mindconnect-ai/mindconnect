package ai.mindconnect.mcp.gateway;

import java.util.List;

/**
 * A server somebody else published, as a catalog offers it — enough to
 * decide whether to register it, and enough to prefill the form.
 *
 * <p>{@code target} is a <em>suggestion</em>: it carries the image or URL the
 * catalog names, with the environment variables the server needs present but
 * empty. Nothing here has been contacted or verified — a catalog entry is a
 * claim by whoever published it, and the operator is the one who decides to
 * run it (concept 21 §9.1).
 *
 * @param id           the catalog's identifier, unique within that catalog
 * @param title        name for humans
 * @param description  what it does
 * @param iconUrl      icon to show, or null
 * @param sourceUrl    where the server's source lives, for a look before running it
 * @param target       the registration this entry suggests
 * @param toolNames    what it says it offers, without starting it
 * @param requiredEnv  environment variables the operator has to fill in
 */
public record McpCatalogEntry(
        String id,
        String title,
        String description,
        String iconUrl,
        String sourceUrl,
        McpTarget target,
        List<String> toolNames,
        List<RequiredValue> requiredEnv
) {

    public McpCatalogEntry {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
    }

    /**
     * One value the operator must supply, as the catalog describes it.
     *
     * @param envName      the environment variable, e.g. {@code GITHUB_PERSONAL_ACCESS_TOKEN}
     * @param description  what it is and where to get it
     * @param secret       true when the value is a credential — a UI must not
     *                     show it, log it, or keep it in a draft
     */
    public record RequiredValue(String envName, String description, boolean secret) {
    }
}
