package ai.mindconnect.agentrest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code POST /api/sessions}. The session belongs to the authenticated
 * caller, so there is no user in it; a {@code userId} an older client still
 * sends is ignored rather than refused — whatever the host's mapper is set to
 * do with unknown fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartSessionRequest(String agentId) {}
