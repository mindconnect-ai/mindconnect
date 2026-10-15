package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiTokenId;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Objects;

/**
 * A request authenticated by a personal API token: it runs as the token's
 * owner. The secret is not kept — only which token it was.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final UserId userId;
    private final ApiTokenId tokenId;

    public ApiTokenAuthentication(UserId userId, ApiTokenId tokenId) {
        super(AuthorityUtils.createAuthorityList("ROLE_USER"));
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public UserId getPrincipal() {
        return userId;
    }

    @Override
    public String getName() {
        return userId.value();
    }

    /** The token that authenticated the request. */
    public ApiTokenId tokenId() {
        return tokenId;
    }
}
