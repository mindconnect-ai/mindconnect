package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpServerRegistration;

/**
 * What is in the form right now — text, not domain.
 *
 * <p>A {@link McpServerRegistration} refuses to exist without an id, a
 * prefix and a valid target, which is right for a registration and wrong
 * for a form somebody is still filling in: the moment one field is missing,
 * there is no object left to render, and the form comes back empty with
 * everything typed lost. This record has no such opinion, so a rejected
 * form can be shown again exactly as it was submitted.
 */
record McpServerDraft(
        String id,
        String displayName,
        String description,
        boolean enabled,
        String toolNamePrefix,
        String targetJson
) {

    static McpServerDraft empty() {
        return new McpServerDraft("", "", "", true, "", McpTargetForm.toJson(null));
    }

    static McpServerDraft of(McpServerRegistration registration) {
        return new McpServerDraft(
                registration.id().value(),   // a draft holds text, not the domain value
                registration.displayName(),
                registration.description(),
                registration.enabled(),
                registration.toolNamePrefix(),
                McpTargetForm.toJson(registration.target()));
    }

    /** The same draft with an id filled in — used where the id comes from the URL. */
    McpServerDraft withId(String value) {
        return new McpServerDraft(value, displayName, description, enabled, toolNamePrefix, targetJson);
    }
}
