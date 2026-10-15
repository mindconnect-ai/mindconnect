package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A namespace's decisions about extensions on the file system: one JSON
 * document per decision under {@code <storageDir>/<namespace>/system/extensions/<id>.json}.
 * Bound to one namespace at construction, like every other store. No file
 * means no decision — the manifest's default applies.
 */
public class FileExtensionActivationRepository implements ExtensionActivationRepository {

    private static final String DIR = "system/extensions";

    private final Documents<ExtensionId, ExtensionActivation> documents;

    public FileExtensionActivationRepository(Path storageDir, ObjectMapper objectMapper, Namespace namespace) {
        FileRepo repo = FileRepo.open(storageDir, namespace.value());
        this.documents = Documents.of(ExtensionActivation.class)
                .path((ExtensionId id) -> DIR + "/" + id.value() + ".json")
                .prettyPrint()
                .build(repo, objectMapper);
    }

    @Override
    public Optional<ExtensionActivation> find(ExtensionId id) {
        // The path is built from the id; a file named like another id is not this decision.
        return documents.find(id).filter(activation -> activation.extensionId().equals(id));
    }

    @Override
    public List<ExtensionActivation> all() {
        return documents.findAll(DIR).stream()
                .sorted(Comparator.comparing(activation -> activation.extensionId().value()))
                .toList();
    }

    @Override
    public void save(ExtensionActivation activation) {
        documents.put(activation.extensionId(), activation);
    }

    @Override
    public void delete(ExtensionId id) {
        documents.delete(id);
    }
}
