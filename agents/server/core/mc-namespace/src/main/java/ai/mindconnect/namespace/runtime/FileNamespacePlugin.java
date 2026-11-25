package ai.mindconnect.namespace.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.adapter.file.FileNamespaceRepository;
import ai.mindconnect.namespace.domain.Email;
import ai.mindconnect.namespace.port.out.NamespacePurge;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.port.out.UserEmails;
import ai.mindconnect.plugin.PluginRegistry;

/** The namespaces on files, under {@code <dataDir>/system/namespaces}, written by the runtime's mapper. */
public final class FileNamespacePlugin extends NamespacePlugin {

    private final Path dataDir;

    /** @param dataDir the installation's data directory ({@code mindconnect.data.base-dir}) */
    public FileNamespacePlugin(Path dataDir, EncryptionHelper encryption, Namespace defaultNamespace,
                               String emailDomain, List<Email> defaultAdmins, UserEmails emails,
                               Supplier<List<NamespacePurge>> purges) {
        super(encryption, defaultNamespace, emailDomain, defaultAdmins, emails, purges);
        this.dataDir = dataDir;
    }

    @Override
    protected NamespaceRepository repository(PluginRegistry registry) {
        return new FileNamespaceRepository(dataDir, registry.get(ObjectMapper.class));
    }
}
