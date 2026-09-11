package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.UserId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * The caller of the current request, for controllers ({@link CurrentUser})
 * and for services that act on the caller's behalf.
 *
 * <p>The host's {@link CurrentUserResolver} decides. A host without one gets
 * the fixed dev user ({@code mindconnect.auth.dev-user}) while authentication
 * is off — the behaviour of an installation without login — and nobody at all
 * once {@code mindconnect.auth.enabled=true}: switching authentication on
 * without a way to tell who is calling must close the API, not open it to a
 * default user.
 */
@Component
public class CurrentUsers {

    private final ObjectProvider<CurrentUserResolver> resolver;
    private final boolean authEnabled;
    private final UserId devUser;

    public CurrentUsers(ObjectProvider<CurrentUserResolver> resolver,
                        @Value("${mindconnect.auth.enabled:false}") boolean authEnabled,
                        @Value("${mindconnect.auth.dev-user:mc_user}") String devUser) {
        this.resolver = resolver;
        this.authEnabled = authEnabled;
        this.devUser = UserId.of(devUser);
    }

    /** The authenticated caller; empty when there is none. */
    public Optional<UserId> current() {
        CurrentUserResolver host = resolver.getIfAvailable();
        if (host != null) {
            return host.currentUser();
        }
        return authEnabled ? Optional.empty() : Optional.of(devUser);
    }

    /** The authenticated caller, or a 401 for the request. */
    public UserId require() {
        return current().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
