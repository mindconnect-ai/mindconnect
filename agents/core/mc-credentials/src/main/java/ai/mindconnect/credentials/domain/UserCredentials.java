package ai.mindconnect.credentials.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Stored credentials for an {@link ExternalIdentity}. Persisted encrypted at
 * rest by the file repository.
 *
 * <p>Sealed: extending this is a deliberate design change, not a configuration
 * change. {@link OAuth2UserCreds} covers the OAuth2 case (vendor MCP servers
 * with browser-based consent), {@link ApiKeyCreds} the static-token case
 * (community servers, on-prem connectors).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OAuth2UserCreds.class, name = "oauth2"),
        @JsonSubTypes.Type(value = ApiKeyCreds.class,     name = "apikey")
})
public sealed interface UserCredentials permits OAuth2UserCreds, ApiKeyCreds {}
