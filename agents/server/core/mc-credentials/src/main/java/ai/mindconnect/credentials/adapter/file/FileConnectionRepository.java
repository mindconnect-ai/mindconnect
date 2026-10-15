package ai.mindconnect.credentials.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.port.out.ConnectionRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link ConnectionRepository} on the file system: one JSON document per
 * connection under {@code <storageDir>/system/connections/<id>.json} —
 * installation-wide, beside the users and their tokens, because an account
 * follows the person and not the namespace they happen to work in.
 *
 * <p>A lookup by user reads the directory: a person attaches a handful of
 * accounts, not millions, and the Postgres adapter indexes the owner.
 */
public class FileConnectionRepository implements ConnectionRepository {

    /** The installation's own partition, beside the namespaces. */
    public static final String SYSTEM = "system";
    private static final String DIR = "connections";

    private final Documents<ConnectionId, Connection> documents;

    public FileConnectionRepository(Path storageDir, ObjectMapper objectMapper) {
        FileRepo repo = FileRepo.open(storageDir, SYSTEM);
        this.documents = Documents.of(Connection.class)
                .path((ConnectionId id) -> DIR + "/" + id.value() + ".json")
                .prettyPrint()
                .build(repo, objectMapper);
    }

    @Override
    public void save(Connection connection) {
        documents.put(connection.id(), connection);
    }

    @Override
    public Optional<Connection> findById(ConnectionId id) {
        // The path is built from the id; a file named like another id is not this connection.
        return documents.find(id).filter(c -> c.id().equals(id));
    }

    @Override
    public List<Connection> findByUser(UserId userId) {
        return documents.findAll(DIR).stream().filter(c -> c.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(ConnectionId id) {
        documents.delete(id);
    }
}
