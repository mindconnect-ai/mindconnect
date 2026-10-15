package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Stores what the modules on the classpath brought along, once.
 *
 * <p>Run at start-up. A registration whose name already exists is left exactly
 * as it is — that is how an operator's edit survives a restart, and how a
 * module upgrade does not silently change where users are sent to sign in.
 */
public class OAuthProviderContributions {

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderContributions.class);

    private final OAuthProviderRepository providers;
    private final List<OAuthProviderContribution> contributions;

    /** What the classpath offers. */
    public OAuthProviderContributions(OAuthProviderRepository providers) {
        this(providers, fromClasspath());
    }

    public OAuthProviderContributions(OAuthProviderRepository providers,
                                      List<OAuthProviderContribution> contributions) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    /**
     * Stores every contribution the installation does not have yet, and puts
     * the operator's own client id on one it already has.
     *
     * @return the names actually stored or updated
     */
    public List<String> install() {
        List<String> stored = new ArrayList<>();
        for (OAuthProviderContribution contribution : contributions) {
            OAuthProvider provider;
            try {
                provider = withOperatorCredentials(contribution);
            } catch (RuntimeException e) {
                log.warn("Skipping a broken OAuth provider contribution from {}: {}",
                        contribution.getClass().getName(), e.getMessage());
                continue;
            }
            if (provider == null) {
                // Nothing to contribute yet: a module whose provider has no
                // shareable registration, waiting for the operator to make one.
                continue;
            }
            Optional<OAuthProvider> existing = providers.findByName(provider.name());
            if (existing.isPresent()) {
                // Theirs now, not ours — except for the credentials the operator
                // hands in through the environment: those are their decision of
                // today, and they may have been made after the first start.
                OAuthProvider refreshed = withOperatorCredentials(existing.get(), contribution);
                if (refreshed.equals(existing.get())) {
                    continue;
                }
                providers.save(refreshed);
                stored.add(refreshed.name());
                log.info("The OAuth provider '{}' now uses the operator's client id", refreshed.name());
                continue;
            }
            if (provider.clientSecret() != null && !provider.clientSecret().isBlank()
                    && contribution.clientSecret() == null) {
                log.warn("The OAuth provider '{}' ships a client secret; a registration that travels with a "
                        + "jar is a public client and cannot keep one. Storing it without.", provider.name());
                provider = provider.withClientSecret(null);
            }
            providers.save(provider);
            stored.add(provider.name());
            log.info("Registered the OAuth provider '{}' from the classpath", provider.name());
        }
        return List.copyOf(stored);
    }

    /** The shipped registration, with whatever credentials the operator supplied. */
    private static OAuthProvider withOperatorCredentials(OAuthProviderContribution contribution) {
        OAuthProvider provider = contribution.provider();
        if (provider == null) {
            return null;                    // nothing to contribute yet
        }
        return withOperatorCredentials(provider, contribution);
    }

    /** {@code provider} — shipped or already stored — with the operator's client id and secret on it. */
    private static OAuthProvider withOperatorCredentials(OAuthProvider provider, OAuthProviderContribution contribution) {
        String theirId = contribution.clientId();
        String theirSecret = contribution.clientSecret();
        boolean newId = theirId != null && !theirId.isBlank() && !theirId.equals(provider.clientId());
        boolean newSecret = theirSecret != null && !theirSecret.isBlank();
        if (!newId && !newSecret) {
            return provider;
        }
        return new OAuthProvider(provider.id(), provider.name(), provider.kind(),
                newId ? theirId.strip() : provider.clientId(),
                newSecret ? theirSecret.strip() : provider.clientSecret(),
                provider.authzUrl(), provider.tokenUrl(),
                provider.defaultScopes(), provider.usePkce(),
                provider.extraAuthzParams(), provider.additionalParams());
    }

    private static List<OAuthProviderContribution> fromClasspath() {
        List<OAuthProviderContribution> found = new ArrayList<>();
        ServiceLoader.load(OAuthProviderContribution.class).forEach(found::add);
        return found;
    }
}
