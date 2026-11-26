package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.chatui.service.SessionOwnership;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * What a tool returned, whole, as a file. A tool card draws only the
 * beginning of a long result; the session keeps all of it, and this is where
 * the reader gets it.
 *
 * <p>Like every session-addressed chat endpoint, a session that is not the
 * caller's is answered with a 404: a tool's output is what its user had the
 * agent read.
 */
@RestController
public class ToolResultDownloadController {

    private static final String PATH = "/chat/api/sessions/{sessionId}/tool-results/{messageId}";

    private final SessionManagement sessions;
    private final SessionOwnership ownership;

    public ToolResultDownloadController(AgentRuntime runtime, SessionOwnership ownership) {
        this.sessions = runtime.service(SessionManagement.class);
        this.ownership = ownership;
    }

    /** Where the result kept as this message of this session is downloaded; {@code null} for a message without an id. */
    public static String url(SessionId sessionId, String messageId) {
        return sessionId == null || messageId == null ? null
                : PATH.replace("{sessionId}", sessionId.value()).replace("{messageId}", messageId);
    }

    @GetMapping(PATH)
    public ResponseEntity<byte[]> download(@PathVariable("sessionId") String sessionIdValue,
                                           @PathVariable String messageId,
                                           @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (ownership.owned(sessionId, user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return sessions.messages(SessionId.of(sessionIdValue)).stream()
                .filter(message -> messageId.equals(message.id()))
                .map(ManagedMessage::message)
                .filter(message -> message.type() == TurnMessage.Type.TOOL_RESULT)
                .findFirst()
                .map(result -> ResponseEntity.ok()
                        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName(result) + "\"")
                        .body(result.text().getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Named after the tool, so several downloads of one chat can be told apart. */
    private static String fileName(TurnMessage result) {
        String tool = result.metadata().get("toolName") instanceof String name ? name : "tool";
        return tool.replaceAll("[^A-Za-z0-9_.-]", "_") + "-result-" + result.id().replaceAll("[^A-Za-z0-9_.-]", "_")
                + ".txt";
    }
}
