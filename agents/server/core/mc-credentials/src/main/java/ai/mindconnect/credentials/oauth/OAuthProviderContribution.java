package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.domain.OAuthProvider;

/**
 * An app registration a module brings with it.
 *
 * <p>A tool module that signs users in somewhere needs two things on the
 * installation: the tools, and the registration they sign in through. The
 * tools arrive by {@code ServiceLoader} already; this is the other half, by
 * the same route — put the implementation in
 * {@code META-INF/services/ai.mindconnect.credentials.oauth.OAuthProviderContribution}
 * and dropping the jar on the classpath is again all there is to it.
 *
 * <p><b>What an operator decided wins.</b> A contribution is stored once, when
 * nothing of that {@link OAuthProvider#name() name} exists yet; from then on
 * it is theirs — an operator who registered an application of their own, with
 * their own client id and perhaps a secret, does not have it overwritten on
 * the next restart. {@link #clientId()} exists so the usual middle case —
 * keep the shipped endpoints and scopes, use my client id — needs no editing
 * at all.
 *
 * <p>A client <em>secret</em> is deliberately not part of this. A registration
 * that ships with a jar is a public client by definition: whoever has the jar
 * has whatever it carries. Such an app proves possession with PKCE instead.
 */
public interface OAuthProviderContribution {

    /**
     * The registration to store when the installation has none of this name,
     * or <b>null when there is nothing to contribute yet</b> — a module whose
     * provider has no shareable registration at all, and whose client the
     * operator must therefore register themselves, says so by returning null
     * until they have. Nothing is stored and nothing is logged as broken.
     *
     * <p>Its {@code clientSecret} must be null; a secret belongs in
     * {@link #clientSecret()}, which reads the operator's, not the jar's.
     */
    OAuthProvider provider();

    /**
     * Where an operator's own client id comes from, when they have one —
     * typically an environment variable this module reads. Null or blank
     * leaves {@link #provider()}'s own client id in place.
     *
     * <p>Kept apart from {@link #provider()} so that the common override does
     * not require restating the endpoints, the scopes and the PKCE setting
     * that the module knows better than the operator does.
     */
    default String clientId() {
        return null;
    }

    /**
     * The operator's client secret, when their provider has no public-client
     * model and insists on one — Google does for server-side redemption, where
     * Microsoft does not. Typically an environment variable this module reads.
     *
     * <p>This is allowed to hold a real secret because it comes from the
     * installation, not from the jar. {@link #provider()} is not: a value
     * that ships with a download is not a secret, and the installer drops one
     * it finds there.
     */
    default String clientSecret() {
        return null;
    }
}
