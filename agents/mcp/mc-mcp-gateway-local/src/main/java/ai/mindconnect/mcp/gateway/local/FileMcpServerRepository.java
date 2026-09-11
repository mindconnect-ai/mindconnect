package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Registrations as one JSON file per server:
 * {@code <storage>/<namespace>/system/mcp-servers/<id>.json} — where every
 * configuration store keeps its documents, agents and LLM configs alike.
 * Bound to one namespace at construction; one process serves one.
 *
 * <p>The directory is the source of truth — dropping a file in makes a
 * server appear, and an operator can diff and version them.
 *
 * <p>Re-reading on every call is affordable because the caller above caches
 * (see {@code LocalMcpGateway}); it keeps the repository stateless and lets a
 * file edit take effect without a restart. A broken file is skipped with a
 * warning: one bad registration must not cost the others.
 */
public final class FileMcpServerRepository implements McpServerRepository {

    private static final Logger log = LoggerFactory.getLogger(FileMcpServerRepository.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path directory;

    public FileMcpServerRepository(Path storageDir, Namespace namespace) {
        this.directory = storageDir.resolve(namespace.value()).resolve("system")
                .resolve("mcp-servers").toAbsolutePath();
    }

    /** Where the registrations live — and where bundled ones are seeded. */
    Path directory() {
        return directory;
    }

    @Override
    public Optional<McpServerRegistration> findById(McpServerId id) {
        return findAll().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public Optional<McpServerRegistration> findByName(String name) {
        return findAll().stream()
                .filter(r -> r.displayName().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public List<McpServerRegistration> findAll() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Map<McpServerId, McpServerRegistration> byId = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : sorted) {
                read(file).ifPresent(registration -> {
                    McpServerRegistration clash = byId.putIfAbsent(registration.id(), registration);
                    if (clash != null) {
                        log.warn("MCP registration '{}' in {} duplicates an earlier file — ignored",
                                registration.id(), file);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list MCP registrations in " + directory, e);
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    /**
     * Writes the registration to {@code <id>.json}, replacing what was there.
     * Written to a temporary file and moved into place, so a reader listing
     * the directory never sees a half-written registration.
     */
    @Override
    public void save(McpServerRegistration registration) {
        Path file = fileFor(registration.id());
        try {
            AtomicFiles.write(file, out -> MAPPER.writerWithDefaultPrettyPrinter().writeValue(out,
                    McpRegistrationJson.write(registration, JsonNodeFactory.instance)));
            log.info("MCP registration '{}' saved to {}", registration.id(), file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save MCP registration '" + registration.id() + "'", e);
        }
    }

    @Override
    public void deleteById(McpServerId id) {
        try {
            if (Files.deleteIfExists(fileFor(id))) {
                log.info("MCP registration '{}' deleted", id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete MCP registration '" + id + "'", e);
        }
    }

    /**
     * The file a server is stored in. The id is a safe file name because
     * {@link McpServerId} refuses anything else — checked there, once, for
     * every way a registration can arrive, and not sanitised here: a silently
     * renamed id would not be findable again.
     */
    private Path fileFor(McpServerId id) {
        return directory.resolve(id.value() + ".json");
    }

    /**
     * Changes when a file is added, removed or written. Coarse on purpose —
     * it only has to differ, and file modification times are what a directory
     * can offer without keeping state.
     */
    @Override
    public long version() {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (Stream<Path> files = Files.list(directory)) {
            long acc = 17L;
            for (Path file : files.sorted().toList()) {
                acc = 31 * acc + file.getFileName().toString().hashCode();
                acc = 31 * acc + Files.getLastModifiedTime(file).toMillis();
            }
            return acc;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stat MCP registrations in " + directory, e);
        }
    }

    private Optional<McpServerRegistration> read(Path file) {
        try {
            return Optional.of(McpRegistrationJson.read(MAPPER.readTree(file.toFile()), file.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("MCP registration {} is unusable and was skipped: {}", file, e.toString());
            return Optional.empty();
        }
    }
}
