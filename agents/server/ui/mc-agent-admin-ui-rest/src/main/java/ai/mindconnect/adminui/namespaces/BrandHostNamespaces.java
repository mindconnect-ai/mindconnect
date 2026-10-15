package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.Branding;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.starter.namespace.HostNamespaces;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers which namespace a host stands for, from the branding: a brand that
 * owns a namespace binds every one of its hosts to it.
 *
 * <p>This is the whole of "the address bar decides" — the scope binding asks,
 * the branding knows, and neither module has to learn about the other.
 */
@Component
public class BrandHostNamespaces implements HostNamespaces {

    private final BrandingProperties branding;

    public BrandHostNamespaces(BrandingProperties branding) {
        this.branding = Objects.requireNonNull(branding, "branding");
    }

    /**
     * Every namespace a brand of this installation owns — read from the
     * configuration on each call, which is a walk over a handful of entries and
     * keeps a reloaded configuration honest.
     */
    @Override
    public Set<Namespace> bound() {
        Set<Namespace> all = new LinkedHashSet<>();
        for (var variant : branding.getSwitch().values()) {
            if (variant.getNamespace() == null) continue;
            branding.namespaceOf(variant).ifPresent(id -> all.add(new Namespace(id)));
        }
        return all;
    }

    @Override
    public Optional<Namespace> namespaceOf(String host) {
        if (host == null) return Optional.empty();
        Branding brand = branding.resolve(host);
        if (!brand.hasNamespace()) return Optional.empty();
        return Optional.of(new Namespace(brand.namespace()));
    }
}
