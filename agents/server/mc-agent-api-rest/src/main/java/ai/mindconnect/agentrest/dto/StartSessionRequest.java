package ai.mindconnect.agentrest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Body of {@code POST /api/sessions}. The session belongs to the authenticated
 * caller, so there is no user in it; a {@code userId} an older client still
 * sends is ignored rather than refused — whatever the host's mapper is set to
 * do with unknown fields.
 *
 * @param agentId        the agent the session runs
 * @param workingDir     the directory the session works in — the file tools'
 *                       base directory, named in the prompt, inherited by
 *                       sub-agents — or {@code null} for the server's
 *                       default. Must exist on the server and lie under
 *                       {@code mindconnect.tools.working-dir-root}.
 * @param additionalDirs further directories the session may reach by
 *                       absolute path, validated the same way; empty or
 *                       {@code null} for none.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartSessionRequest(String agentId, String workingDir, List<String> additionalDirs) {

    /** Without a working directory. */
    public StartSessionRequest(String agentId) {
        this(agentId, null, List.of());
    }

    /** With a working directory alone. */
    public StartSessionRequest(String agentId, String workingDir) {
        this(agentId, workingDir, List.of());
    }
}
