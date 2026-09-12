package ai.mindconnect.agent.registry.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.common.Versions;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Stores configured registries under
 * {@code {base}/{namespace}/system/registries/{id}.json} — beside the agents
 * and LLM configs they install.
 *
 * <p>A file each, so an installation can ship a registry the way it ships a
 * seed agent: drop the JSON in and it is there on the next start.
 */
public class FileRegistrySourceRepository implements RegistrySourceRepository {

    private static final String DIR = "system/registries";

    private final Documents<RegistrySourceId, RegistrySource> sources;
    private final Path directory;

    public FileRegistrySourceRepository(Path storageDir, Namespace namespace) {
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        FileRepo repo = FileRepo.open(storageDir, namespace.value());
        this.directory = repo.resolve(DIR);
        this.sources = Documents.of(RegistrySource.class)
                .path((RegistrySourceId id) -> DIR + "/" + id.value() + ".json")
                .build(repo, objectMapper);
    }

    /** Where the files are — what an initial-data installer seeds into. */
    public Path directory() {
        return directory;
    }

    @Override
    public RegistrySource save(RegistrySource source) {
        return sources.compute(source.id(), current -> source.withVersion(Versions.next(
                current.map(RegistrySource::version).orElse(null), source.version(),
                "RegistrySource", source.id().value())));
    }

    @Override
    public Optional<RegistrySource> findById(RegistrySourceId id) {
        return sources.find(id).filter(source -> source.id().equals(id));
    }

    @Override
    public List<RegistrySource> findAll() {
        return sources.findAll(DIR).stream()
                .sorted(Comparator.comparing(RegistrySource::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public void deleteById(RegistrySourceId id) {
        sources.delete(id);
    }
}
