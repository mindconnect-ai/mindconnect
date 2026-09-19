package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.setup.PendingAuthorization;
import ai.mindconnect.adminui.setup.ToolConnections;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Acquisition;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.oauth.OAuthConnections;
import ai.mindconnect.credentials.oauth.OAuthException;
import ai.mindconnect.credentials.oauth.OAuthFlow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The two ends of signing in at a provider: away to it, and back.
 *
 * <p>Both are plain browser navigations rather than the API calls the rest of
 * this screen uses — a consent page cannot be fetched into a dialog, and the
 * provider redirects the browser, not a script.
 *
 * <p>What is trusted on the way back: the {@code state} this session
 * generated, and nothing else. A callback that does not echo it is somebody
 * else's — or somebody trying to attach their account to this user — and is
 * refused before the code is redeemed.
 */
@RestController
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    /** Where the provider sends the browser back. Registered with the app, so it must not move. */
    public static final String CALLBACK = "/admin/oauth/callback";
    private static final String PROFILE = "/admin/profile";

    private final ToolConnections connections;
    private final ObjectProvider<OAuthConnections> oauth;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthCallbackController(ToolConnections connections, ObjectProvider<OAuthConnections> oauth) {
        this(connections, oauth, Clock.systemUTC());
    }

    OAuthCallbackController(ToolConnections connections, ObjectProvider<OAuthConnections> oauth, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Away: build the authorization URL, remember the attempt, send the browser. */
    @GetMapping("/admin/oauth/authorize/{provider}")
    public ResponseEntity<Void> authorize(@PathVariable("provider") String provider, HttpServletRequest request) {
        OAuthConnections service = oauth.getIfAvailable();
        ConnectionSpec spec = connections.spec(provider).orElse(null);
        Acquisition.OAuth acquisition = spec == null ? null : oauthAcquisition(spec).orElse(null);
        if (service == null || acquisition == null) {
            return back("This installation cannot sign you in to " + provider + ".");
        }
        try {
            String redirectUri = callbackUri();
            OAuthFlow.Start start = service.begin(acquisition.providerName(), redirectUri, acquisition.scopes());
            new PendingAuthorization(acquisition.providerName(), provider, start.state(),
                    start.codeVerifier(), redirectUri, clock.instant()).rememberOn(request);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(start.authorizeUrl())).build();
        } catch (OAuthException e) {
            return back(e.getMessage());
        }
    }

    /**
     * Back: the provider's answer. Every way this can go wrong ends on the
     * profile with a sentence, because a blank error page in the middle of a
     * consent flow tells nobody anything.
     */
    @GetMapping(CALLBACK)
    public ResponseEntity<Void> callback(@AuthenticationPrincipal OidcUser user,
                                         @RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         @RequestParam(value = "error_description", required = false) String detail,
                                         HttpServletRequest request) {
        PendingAuthorization pending = PendingAuthorization.claim(request, state, clock.instant());
        if (error != null) {
            // The user said no, or the provider refused. Their words, not ours.
            return back(detail != null ? detail : "The provider refused: " + error);
        }
        if (pending == null) {
            return back("That sign-in could not be matched to this browser. Start it again.");
        }
        if (code == null || code.isBlank()) {
            return back("The provider sent no code back. Try connecting again.");
        }
        OAuthConnections service = oauth.getIfAvailable();
        if (service == null) {
            return back("This installation cannot store that connection.");
        }
        try {
            Connection created = service.complete(userId(user), pending.connectionProvider(),
                    pending.providerName(), label(pending), code, pending.codeVerifier(), pending.redirectUri());
            return back("\"" + created.label() + "\" is connected.");
        } catch (OAuthException e) {
            log.info("Could not complete the {} sign-in: {}", pending.providerName(), e.getMessage());
            return back(e.getMessage());
        }
    }

    /**
     * What the new connection is called. The provider is not asked who the
     * account belongs to — that is a second call and a different scope per
     * vendor — so it gets the card's name, and the user renames it if they
     * keep two. Renaming is safe: the key is fixed when it is created.
     */
    private String label(PendingAuthorization pending) {
        return connections.spec(pending.connectionProvider())
                .map(ConnectionSpec::title)
                .orElse(pending.connectionProvider());
    }

    private static Optional<Acquisition.OAuth> oauthAcquisition(ConnectionSpec spec) {
        return spec.acquisitions().stream()
                .filter(Acquisition.OAuth.class::isInstance).map(Acquisition.OAuth.class::cast)
                .findFirst();
    }

    /** The exact URI the provider was told and must be registered with the app. */
    private static String callbackUri() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(CALLBACK).toUriString();
    }

    /** Back to the Connections tab, with something to read. */
    private static ResponseEntity<Void> back(String message) {
        String query = "?connected=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(PROFILE + query)).build();
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(user == null ? "mc_user" : user.getPreferredUsername());
    }
}
