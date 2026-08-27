package ai.mindconnect.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes {@link CliState} to {@code ~/.mindconnect-cli/state.json}.
 * <p>
 * Failure-tolerant: a missing file, malformed JSON, or any IO error returns
 * {@link CliState#empty()} and logs a warning. Writes are best-effort — if the
 * file cannot be written the CLI continues to work, the user just won't be
 * resumed on the next start.
 * <p>
 * Writes are atomic: data goes to {@code state.json.tmp} first and is then
 * moved over {@code state.json} with {@link StandardCopyOption#ATOMIC_MOVE}
 * so a crash mid-write can never leave a half-written state file.
 */
@Component
public class CliStateStore {

    private static final Logger log = LoggerFactory.getLogger(CliStateStore.class);
    private static final String DIR_NAME = ".mindconnect-cli";
    private static final String FILE_NAME = "state.json";

    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public CliStateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Path home = Path.of(System.getProperty("user.home", "."));
        this.stateFile = home.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    public CliState load() {
        if (!Files.exists(stateFile)) return CliState.empty();
        try {
            return objectMapper.readValue(stateFile.toFile(), CliState.class);
        } catch (IOException e) {
            log.warn("Could not read CLI state from {} — starting at agent list. Reason: {}",
                    stateFile, e.getMessage());
            return CliState.empty();
        }
    }

    public void save(CliState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(FILE_NAME + ".tmp");
            objectMapper.writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to plain replace.
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not save CLI state to {}: {}", stateFile, e.getMessage());
        }
    }
}
