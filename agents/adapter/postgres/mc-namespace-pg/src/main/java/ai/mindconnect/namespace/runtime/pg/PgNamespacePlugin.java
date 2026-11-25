package ai.mindconnect.namespace.runtime.pg;

import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.namespace.adapter.pg.PgNamespaceRepository;
import ai.mindconnect.namespace.domain.Email;
import ai.mindconnect.namespace.port.out.NamespacePurge;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.port.out.UserEmails;
import ai.mindconnect.namespace.runtime.NamespacePlugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The namespaces in Postgres, one row each in {@code mc_namespace}, on the runtime's {@link Sql}. */
public final class PgNamespacePlugin extends NamespacePlugin {

    public PgNamespacePlugin(EncryptionHelper encryption, Namespace defaultNamespace, String emailDomain,
                             List<Email> defaultAdmins, UserEmails emails, Supplier<List<NamespacePurge>> purges) {
        super(encryption, defaultNamespace, emailDomain, defaultAdmins, emails, purges);
    }

    @Override
    protected NamespaceRepository repository(PluginRegistry registry) {
        return new PgNamespaceRepository(registry.get(Sql.class)).initSchema();
    }
}
