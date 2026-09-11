package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Objects;

/**
 * Turns a validated JWT into the request's authentication. The caller is the
 * {@value #USER_ID_CLAIM} claim — the same value a browser login yields, so a
 * user's sessions are the same whichever way they authenticate. A token
 * without that claim is rejected rather than mapped to another identity.
 */
public class JwtUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** The claim that becomes the {@link UserId}. */
    public static final String USER_ID_CLAIM = "preferred_username";

    private final UserRecorder recorder;

    public JwtUserConverter(UserRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String name = jwt.getClaimAsString(USER_ID_CLAIM);
        if (name == null || name.isBlank()) {
            throw new InvalidBearerTokenException("The token carries no " + USER_ID_CLAIM + " claim");
        }
        recorder.record(UserId.of(name), jwt.getSubject(),
                jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"), name);
    }
}
