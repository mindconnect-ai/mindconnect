package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which bundled records a namespace got once, on the file system: one
 * document, {@code <storageDir>/<namespace>/system/installed-seeds.json},
 * listing them. One file rather than one per record because a record's name
 * is whatever its seed says — an agent may be called {@code Summarizer} or
 * {@code My agent} — and does not have to make a good file name. Bound to one
 * namespace at construction, like every other store; deleting the namespace
 * deletes the file with its directory.
 */
public class FileInstalledSeedRepository implements InstalledSeedRepository {

    static final String PATH = "system/installed-seeds.json";

    /** The document: every record, in the order they were first recorded. */
    public record Ledger(List<InstalledSeed> seeds) {
        public Ledger {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
        }
    }

    private final Documents<String, Ledger> documents;

    public FileInstalledSeedRepository(Path storageDir, ObjectMapper objectMapper, Namespace namespace) {
        FileRepo repo = FileRepo.open(storageDir, namespace.value());
        this.documents = Documents.of(Ledger.class)
                .path((String key) -> PATH)
                .prettyPrint()
                .build(repo, objectMapper);
    }

    @Override
    public List<InstalledSeed> all() {
        return documents.find(PATH).map(Ledger::seeds).orElse(List.of());
    }

    @Override
    public void record(Collection<InstalledSeed> seeds) {
        if (seeds.isEmpty()) return;
        documents.compute(PATH, current -> merge(current, seeds));
    }

    private static Ledger merge(Optional<Ledger> current, Collection<InstalledSeed> seeds) {
        Map<String, InstalledSeed> byKey = new LinkedHashMap<>();
        current.ifPresent(ledger -> ledger.seeds().forEach(seed -> byKey.putIfAbsent(seed.key(), seed)));
        int before = byKey.size();
        seeds.forEach(seed -> byKey.putIfAbsent(seed.key(), seed));
        if (current.isPresent() && byKey.size() == before) return current.get(); // nothing new: write nothing
        List<InstalledSeed> sorted = new ArrayList<>(byKey.values());
        sorted.sort(Comparator.comparing(InstalledSeed::kind).thenComparing(InstalledSeed::name));
        return new Ledger(sorted);
    }
}
