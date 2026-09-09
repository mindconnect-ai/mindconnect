package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.UserStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live feed the SPA attaches to from every admin page (see
 * {@code AdminLayout}): the task board as {@code patch} frames and the
 * user's own session events as {@code user} frames, on one connection.
 */
@RestController
public class UserStreamController {

    private final UserStream userStream;

    public UserStreamController(UserStream userStream) {
        this.userStream = userStream;
    }

    @GetMapping(value = UserStream.STREAM_URL, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal OidcUser user) {
        // Long-lived: it outlives every turn and every navigation. Idle time
        // is covered by the heartbeat.
        SseEmitter emitter = new SseEmitter(0L);
        userStream.attach(emitter, userId(user));
        emitter.onCompletion(() -> userStream.detach(emitter));
        emitter.onError(t -> userStream.detach(emitter));
        emitter.onTimeout(() -> userStream.detach(emitter));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Sui-Stream-Channel", UserStream.CHANNEL_ID);
        headers.add("Sui-Stream-Label", "Live updates");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    /** The same identity the chat and the task manager use — the fixed dev user with auth off. */
    private static String userId(OidcUser user) {
        return user == null ? "mc_user" : user.getPreferredUsername();
    }
}
