package ai.mindconnect.agent.security;

import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.service.ApiTokenService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.util.Objects;

/**
 * Authenticates a bearer token that is one of our API tokens. Anything else —
 * a JWT — it leaves to the next provider by returning null; a token with our
 * prefix that does not check out is rejected here and never offered to the
 * JWT provider.
 */
public class ApiTokenAuthenticationProvider implements AuthenticationProvider {

    private final ApiTokenService tokens;
    private final UserRecorder recorder;

    public ApiTokenAuthenticationProvider(ApiTokenService tokens, UserRecorder recorder) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String secret = ((BearerTokenAuthenticationToken) authentication).getToken();
        if (!ApiTokenService.looksLikeApiToken(secret)) {
            return null;
        }
        ApiToken token = tokens.authenticate(secret)
                .orElseThrow(() -> new InvalidBearerTokenException("Invalid, expired or revoked API token"));
        recorder.record(token.userId(), null, null, null, null);
        ApiTokenAuthentication result = new ApiTokenAuthentication(token.userId(), token.id());
        result.setDetails(authentication.getDetails());
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
