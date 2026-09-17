package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;

import java.util.Optional;

/**
 * The namespace a host name stands for, when a host stands for one.
 *
 * <p>An installation that serves several brands from one process
 * ({@code mindconnect.branding.switch}) can give a brand a namespace of its
 * own. Where it does, <strong>the address bar decides where the work
 * happens</strong>: everything under that host is that namespace, and somebody
 * who is not in it does not get in — not with a header, not with a namespace
 * they chose earlier, not because they happen to be in some other one. A brand
 * that is only a name and a logo binds nothing, and there the user's own choice
 * decides as it always did.
 *
 * <p>The rule earns its keep the moment two brands share a process: without it
 * a member of one brand's namespace, arriving under the other brand's name,
 * would work in their own namespace while wearing somebody else's — the page
 * would say ERNI and show what is not ERNI's.
 *
 * <p>Branding lives in the Admin UI, namespaces in this starter, and neither
 * knows the other: the Admin UI answers this question, and the scope binding
 * asks it.
 */
@FunctionalInterface
public interface HostNamespaces {

    /**
     * The namespace {@code host} stands for, or empty when it stands for none.
     *
     * @param host the host name of the request — no port, no path; null off a request
     */
    Optional<Namespace> namespaceOf(String host);

    /** No host binds a namespace: what an installation without brands does. */
    static HostNamespaces none() {
        return host -> Optional.empty();
    }
}
