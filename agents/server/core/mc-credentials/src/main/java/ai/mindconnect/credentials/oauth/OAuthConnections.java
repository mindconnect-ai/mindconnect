package ai.mindconnect.credentials.oauth;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import ai.mindconnect.credentials.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Attaching an account by signing in at the provider, rather than by typing a
 * password into a form.
 *
 * <p>The two halves of the round trip — {@link #begin} hands back where to
 * send the browser and the two values that have to survive until it comes
 * back; {@link #complete} turns the code into a stored connection. What is
 * remembered in between belongs to the browser session, not here: this object
 * is shared by every user of the installation.
 *
 * <p>{@link #ensureFresh} is the third half: a token that is about to run out
 * is renewed before a tool is handed it, so a call never fails on the clock.
 */
public class OAuthConnections {

    private static final Logger log = LoggerFactory.getLogger(OAuthConnections.class);

    /**
     * The setting that records which app registration a connection was made
     * through. Without it a refresh would not know whose token endpoint to
     * ask, and a user with two connections of one provider would be a guess.
     */
    public static final String PROVIDER_SETTING = "oauthProvider";

    private final ConnectionService connections;
    private final OAuthProviderRepository providers;
    private final OAuthFlow flow;
    private final Clock clock;
    /** One lock per connection; a {@link ReentrantLock} because it is held across an HTTP call. */
    private final Map<ConnectionId, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    public OAuthConnections(ConnectionService connections, OAuthProviderRepository providers, OAuthFlow flow) {
        this(connections, providers, flow, Clock.systemUTC());
    }

    public OAuthConnections(ConnectionService connections, OAuthProviderRepository providers,
                            OAuthFlow flow, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether this installation knows the app registration a spec asks for. */
    public Optional<OAuthProvider> provider(String providerName) {
        return providers.findByName(providerName);
    }

    /**
     * Where to send the browser.
     *
     * @throws OAuthException when nothing is registered under that name — an
     *         installation that has the tools but not the app registration
     */
    public OAuthFlow.Start begin(String providerName, String redirectUri, List<String> scopes) {
        OAuthProvider provider = providers.findByName(providerName).orElseThrow(() -> new OAuthException(
                "This installation has no app registration called \"" + providerName + "\", so it cannot "
                        + "sign you in there. An administrator registers one under OAuth providers."));
        return flow.start(provider, redirectUri, scopes);
    }

    /**
     * The code from the callback, as a stored connection.
     *
     * @param connectionProvider what the connection is stored under — the
     *                           {@code ConnectionSpec}'s provider, which may
     *                           differ from the app registration's name
     * @param label              what to call it; the user renames it afterwards if they like
     */
    public Connection complete(UserId user, String connectionProvider, String providerName, String label,
                               String code, String codeVerifier, String redirectUri) {
        OAuthProvider provider = providers.findByName(providerName).orElseThrow(() ->
                new OAuthException("No app registration called \"" + providerName + "\" any more."));
        OAuth2UserCreds credentials = flow.exchange(provider, code, codeVerifier, redirectUri);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PROVIDER_SETTING, providerName);
        Connection created = connections.attach(user, connectionProvider, label, credentials, settings);
        log.info("Connected {} for {} through {}", connectionProvider, user.value(), providerName);
        return created;
    }

    /**
     * The same connection with a token that will still be valid when it is
     * used. A connection that is not OAuth, or whose token has time left, is
     * handed back untouched.
     *
     * <p>One refresh per connection at a time. Two tool calls of one turn
     * often find the same token about to run out, and providers that rotate
     * refresh tokens take each one only once — the second call would be
     * refused and the connection marked expired, although nothing is wrong
     * with it. So the second waits for the first, reads the connection again
     * and uses the token the first one fetched.
     *
     * <p>A refusal is recorded on the connection rather than thrown: the tool
     * then says "connect it again" instead of failing the same way on every
     * call, and the list on the profile says so too.
     */
    public Connection ensureFresh(Connection connection) {
        if (!(connection.credentials() instanceof OAuth2UserCreds seen)
                || !OAuthFlow.needsRefresh(seen, clock.instant())) {
            return connection;
        }
        ReentrantLock lock = refreshLocks.computeIfAbsent(connection.id(), id -> new ReentrantLock());
        lock.lock();
        try {
            Connection current = connections.find(connection.userId(), connection.id()).orElse(connection);
            if (!(current.credentials() instanceof OAuth2UserCreds credentials)) {
                return current;
            }
            if (!OAuthFlow.needsRefresh(credentials, clock.instant())) {
                // Somebody else refreshed it while this call was waiting.
                return current;
            }
            String providerName = current.settings().get(PROVIDER_SETTING);
            OAuthProvider provider = providerName == null ? null : providers.findByName(providerName).orElse(null);
            if (provider == null) {
                return unusable(current, "the app registration it was made with is gone");
            }
            try {
                return connections.replaceCredentials(current.id(), flow.refresh(provider, credentials));
            } catch (OAuthException e) {
                log.info("Could not refresh {} of {}: {}", current.key(), current.userId().value(),
                        e.getMessage());
                return unusable(current, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    private Connection unusable(Connection connection, String reason) {
        connections.markUnusable(connection.id(),
                ai.mindconnect.credentials.domain.ConnectionState.EXPIRED, reason);
        return connection.withState(ai.mindconnect.credentials.domain.ConnectionState.EXPIRED,
                reason, clock.instant());
    }
}
