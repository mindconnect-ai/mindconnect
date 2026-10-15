package ai.mindconnect.credentials.domain;

import java.util.Objects;

/**
 * Static API key / personal access token. No refresh, no expiry.
 *
 * <p>Used for community MCP servers and on-prem connectors where the user
 * pastes a long-lived token (e.g. Atlassian Data Center PAT, GitHub classic
 * PAT, Google service-account key).
 */
public record ApiKeyCreds(String value) implements UserCredentials {
    public ApiKeyCreds {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("API key value must not be blank");
    }
}
