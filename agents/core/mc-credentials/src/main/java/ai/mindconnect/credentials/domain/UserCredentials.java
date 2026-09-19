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
 * (community servers, on-prem connectors), and {@link FormCreds} the case
 * where there is no token at all and somebody types a password — a mailbox
 * over IMAP, an on-prem service with basic auth.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OAuth2UserCreds.class, name = "oauth2"),
        @JsonSubTypes.Type(value = ApiKeyCreds.class,     name = "apikey"),
        @JsonSubTypes.Type(value = FormCreds.class,       name = "form")
})
public sealed interface UserCredentials permits OAuth2UserCreds, ApiKeyCreds, FormCreds {}
