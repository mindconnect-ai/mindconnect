package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
     * Stores every contribution the installation does not have yet.
     *
     * @return the names actually stored
     */
    public List<String> install() {
        List<String> stored = new ArrayList<>();
        for (OAuthProviderContribution contribution : contributions) {
            OAuthProvider provider;
            try {
                provider = withOperatorClientId(contribution);
            } catch (RuntimeException e) {
                log.warn("Skipping a broken OAuth provider contribution from {}: {}",
                        contribution.getClass().getName(), e.getMessage());
                continue;
            }
            if (providers.findByName(provider.name()).isPresent()) {
                continue;                   // theirs now, not ours
            }
            if (provider.clientSecret() != null && !provider.clientSecret().isBlank()) {
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

    /** The shipped registration, with the operator's client id where they gave one. */
    private static OAuthProvider withOperatorClientId(OAuthProviderContribution contribution) {
        OAuthProvider provider = Objects.requireNonNull(contribution.provider(), "provider");
        String theirs = contribution.clientId();
        if (theirs == null || theirs.isBlank() || theirs.equals(provider.clientId())) {
            return provider;
        }
        return new OAuthProvider(provider.id(), provider.name(), provider.kind(), theirs.strip(),
                provider.clientSecret(), provider.authzUrl(), provider.tokenUrl(),
                provider.defaultScopes(), provider.usePkce(),
                provider.extraAuthzParams(), provider.additionalParams());
    }

    private static List<OAuthProviderContribution> fromClasspath() {
        List<OAuthProviderContribution> found = new ArrayList<>();
        ServiceLoader.load(OAuthProviderContribution.class).forEach(found::add);
        return found;
    }
}
