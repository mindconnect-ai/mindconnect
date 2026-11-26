package ai.mindconnect.namespace.runtime;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.env.EnvVarSource;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.adapter.env.EncryptingNamespaceRepository;
import ai.mindconnect.namespace.adapter.env.NamespaceEnvVarResolver;
import ai.mindconnect.namespace.domain.Email;
import ai.mindconnect.namespace.port.out.NamespacePurge;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.port.out.UserEmails;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The namespaces of an installation, in the runtime they feed: the runtime
 * asks a namespace's variables for a configuration's {@code ${VAR}}, so the
 * store lives where the runtime is built, not in the host around it — a host
 * bean would need the runtime's mapper before the runtime exists. A subclass
 * says where the namespaces are kept.
 *
 * <p>Installation-wide: it is the store that says which namespaces exist. A
 * namespace's variables are secrets, kept encrypted when the installation has
 * a key.
 */
public abstract class NamespacePlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(NamespacePlugin.class);

    private final EncryptionHelper encryption;
    private final Namespace defaultNamespace;
    private final String emailDomain;
    private final List<Email> defaultAdmins;
    private final UserEmails emails;
    private final Supplier<List<NamespacePurge>> purges;

    /**
     * @param encryption       what a namespace's variables are encrypted with; {@code null} keeps them plain
     * @param defaultNamespace the installation's own namespace, which nobody deletes
     * @param emailDomain      what a bare name becomes an address with
     * @param defaultAdmins    who shapes the default namespace; empty leaves it open to everybody signed in
     * @param emails           the address behind a user id
     * @param purges           what the stores do with a deleted namespace's data, asked when one is deleted:
     *                         most of them are the host's, made after the runtime
     */
    protected NamespacePlugin(EncryptionHelper encryption, Namespace defaultNamespace, String emailDomain,
                              List<Email> defaultAdmins, UserEmails emails, Supplier<List<NamespacePurge>> purges) {
        this.encryption = encryption;
        this.defaultNamespace = defaultNamespace;
        this.emailDomain = emailDomain;
        this.defaultAdmins = List.copyOf(defaultAdmins);
        this.emails = emails;
        this.purges = purges;
    }

    @Override
    public String name() {
        return "namespaces";
    }

    /** The store, made once the plugins are installed: what it needs is in {@code registry} by then. */
    protected abstract NamespaceRepository repository(PluginRegistry registry);

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(NamespaceRepository.class, () -> encrypting(repository(registry)));
        // One purge that asks for the others when a namespace is deleted: by then the host has made them.
        NamespacePurge every = namespace -> purges.get().forEach(purge -> purge.purge(namespace));
        registry.provide(NamespaceService.class, () -> new NamespaceService(registry.get(NamespaceRepository.class),
                defaultNamespace, Clock.systemUTC(), List.of(every), emails, emailDomain, defaultAdmins));
        registry.contribute(EnvVarSource.class, new EnvVarSource(() -> new NamespaceEnvVarResolver(
                registry.get(NamespaceRepository.class), registry.get(ScopeSupplier.class))));
    }

    private NamespaceRepository encrypting(NamespaceRepository repository) {
        if (encryption == null) {
            log.warn("No EncryptionHelper — a namespace's variables are stored unencrypted");
            return repository;
        }
        EncryptingNamespaceRepository encrypting = new EncryptingNamespaceRepository(repository, encryption);
        int rewritten = encrypting.rewriteEarlierScheme();
        if (rewritten > 0) {
            log.info("{} namespace record(s) rewritten from the earlier encryption scheme to AES-GCM", rewritten);
        }
        return encrypting;
    }
}
