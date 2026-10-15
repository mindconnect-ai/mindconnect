package ai.mindconnect.credentials.adapter.file;

import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OAuthProviderRepository} on the file system: one JSON document per
 * app registration under {@code <storageDir>/system/oauth-providers/<id>.json}
 * — installation-wide, beside the users and the connections made through it.
 */
public class FileOAuthProviderRepository implements OAuthProviderRepository {

    /** The installation's own partition, beside the namespaces. */
    public static final String SYSTEM = "system";
    private static final String DIR = "oauth-providers";

    private final Documents<UUID, OAuthProvider> documents;

    public FileOAuthProviderRepository(Path storageDir, ObjectMapper objectMapper) {
        FileRepo repo = FileRepo.open(storageDir, SYSTEM);
        this.documents = Documents.of(OAuthProvider.class)
                .path((UUID id) -> DIR + "/" + id + ".json")
                .prettyPrint()
                .build(repo, objectMapper);
    }

    @Override
    public void save(OAuthProvider provider) {
        documents.put(provider.id(), provider);
    }

    @Override
    public Optional<OAuthProvider> findById(UUID id) {
        return documents.find(id).filter(provider -> provider.id().equals(id));
    }

    @Override
    public Optional<OAuthProvider> findByName(String name) {
        return name == null ? Optional.empty()
                : findAll().stream().filter(provider -> provider.name().equals(name)).findFirst();
    }

    @Override
    public List<OAuthProvider> findAll() {
        return documents.findAll(DIR).stream()
                .sorted(Comparator.comparing(OAuthProvider::name))
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        documents.delete(id);
    }
}
