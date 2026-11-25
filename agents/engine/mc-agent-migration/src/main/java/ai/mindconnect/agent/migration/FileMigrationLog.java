package ai.mindconnect.agent.migration;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;

/** The log on files: {@code {base}/{namespace}/system/migrations/{id}.json}, one file per migration that ran. */
public final class FileMigrationLog implements MigrationLog {

    private static final String DIRECTORY = "system/migrations";

    private final Documents<String, AppliedMigration> applied;

    public FileMigrationLog(Path base, ObjectMapper json, ScopeSupplier scope) {
        this.applied = Documents.of(AppliedMigration.class)
                .path((String id) -> DIRECTORY + "/" + id + ".json")
                .build(() -> FileRepo.open(base, scope.namespace().value()), json);
    }

    @Override
    public List<AppliedMigration> applied() {
        return applied.findAll(DIRECTORY);
    }

    @Override
    public void record(AppliedMigration migration) {
        applied.put(migration.id(), migration);
    }
}
