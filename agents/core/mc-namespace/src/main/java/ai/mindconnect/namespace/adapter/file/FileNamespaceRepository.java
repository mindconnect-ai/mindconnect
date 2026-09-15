package ai.mindconnect.namespace.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link NamespaceRepository} on the file system: one JSON document per
 * namespace under {@code <storageDir>/system/namespaces/<id>.json}.
 * Installation-wide — beside the namespaces' own directories, not inside
 * one of them.
 */
public class FileNamespaceRepository implements NamespaceRepository {

    /** The installation's own partition, beside the namespaces. */
    public static final String SYSTEM = "system";
    private static final String DIR = "namespaces";

    private final Documents<Namespace, NamespaceDefinition> documents;

    public FileNamespaceRepository(Path storageDir, ObjectMapper objectMapper) {
        FileRepo repo = FileRepo.open(storageDir, SYSTEM);
        this.documents = Documents.of(NamespaceDefinition.class)
                .path((Namespace id) -> DIR + "/" + id.value() + ".json")
                .prettyPrint()
                .build(repo, objectMapper);
    }

    @Override
    public Optional<NamespaceDefinition> findById(Namespace id) {
        // The path is built from the id; a file named like another id is not this namespace.
        return documents.find(id).filter(ns -> ns.id().equals(id));
    }

    @Override
    public List<NamespaceDefinition> findAll() {
        return documents.findAll(DIR).stream()
                .sorted(Comparator.comparing(ns -> ns.id().value()))
                .toList();
    }

    @Override
    public List<NamespaceDefinition> findByMember(UserId user) {
        return findAll().stream().filter(ns -> ns.isMember(user)).toList();
    }

    @Override
    public void save(NamespaceDefinition namespace) {
        documents.put(namespace.id(), namespace);
    }

    @Override
    public boolean deleteById(Namespace id) {
        return documents.delete(id);
    }
}
