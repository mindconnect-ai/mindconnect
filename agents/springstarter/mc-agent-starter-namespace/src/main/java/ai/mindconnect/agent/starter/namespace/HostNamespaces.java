package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;

import java.util.Optional;
import java.util.Set;

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
 * <p>The rule has two sides, and both matter the moment two brands share a
 * process. Under the address that names a namespace, that namespace is the
 * one. And <strong>away from that address it does not exist</strong>: it is
 * not offered in the switcher, a choice made earlier does not carry it over,
 * and a request naming it is refused — otherwise the work of one brand would
 * show up under the other brand's name.
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

    /**
     * Every namespace some host stands for. A namespace in here is reachable
     * under its own address and nowhere else; everything not in here is
     * wherever its members take it.
     */
    default Set<Namespace> bound() {
        return Set.of();
    }

    /** No host binds a namespace: what an installation without brands does. */
    static HostNamespaces none() {
        return host -> Optional.empty();
    }
}
