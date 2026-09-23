package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Location;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One JSON file per window under
 * {@code <dataDir>/<namespace>/mail-index/<user>/<account>/<folder>.json},
 * written to a temporary file and moved over the real one. Bound to one
 * namespace, like every file adapter; the router above picks the one for
 * the request.
 *
 * <p>Every write has a temporary file of its own, and a change of a window
 * ({@link #update}) holds that window's lock from the read to the move: two
 * writers sharing one {@code .tmp} wrote into each other's file, and two
 * changes that each read the window before the other saved lost one of
 * them.
 */
public final class FileMailIndexStore implements MailIndexStore {

    private final java.util.function.Supplier<Path> dataDir;
    private final java.util.function.Supplier<String> namespace;
    private final ObjectMapper json;
    /** One lock per window file; a window is changed by one writer at a time. */
    private final java.util.concurrent.ConcurrentHashMap<Path, java.util.concurrent.locks.ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public FileMailIndexStore(Path dataDir, String namespace) {
        this(() -> dataDir, () -> namespace);
    }

    /**
     * Resolved per call: a server binds the namespace per request, and a
     * store built at start-up has no request to ask.
     */
    public FileMailIndexStore(java.util.function.Supplier<Path> dataDir, java.util.function.Supplier<String> namespace) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public Optional<FolderWindow> load(UserId user, Location location) {
        Path file = file(user, location);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(json.readValue(file.toFile(), FolderWindow.class));
        } catch (IOException e) {
            // A window that cannot be read is one that is filled again.
            return Optional.empty();
        }
    }

    @Override
    public void save(UserId user, FolderWindow window) {
        Path file = file(user, window.location());
        java.util.concurrent.locks.ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            write(file, window);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void update(UserId user, Location location, java.util.function.UnaryOperator<FolderWindow> change) {
        Path file = file(user, location);
        java.util.concurrent.locks.ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            Optional<FolderWindow> current = load(user, location);
            if (current.isEmpty()) return;
            FolderWindow changed = change.apply(current.get());
            if (changed != current.get()) write(file, changed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(UserId user, Location location) {
        Path file = file(user, location);
        java.util.concurrent.locks.ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the mail index for " + location, e);
        } finally {
            lock.unlock();
        }
    }

    /** Into a temporary file of this write's own, then over the real one in one move. */
    private void write(Path file, FolderWindow window) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            json.writeValue(temporary.toFile(), window);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new UncheckedIOException("Could not save the mail index for " + window.location(), e);
        }
    }

    private java.util.concurrent.locks.ReentrantLock lockOf(Path file) {
        return locks.computeIfAbsent(file.toAbsolutePath().normalize(),
                k -> new java.util.concurrent.locks.ReentrantLock());
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Only a leftover; the next write does not need it gone.
        }
    }

    @Override
    public List<Location> windows(UserId user) {
        Path dir = root().resolve(segment(user.value()));
        List<Location> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> accounts = Files.list(dir)) {
            for (Path account : accounts.toList()) {
                if (!Files.isDirectory(account)) continue;
                try (Stream<Path> files = Files.list(account)) {
                    for (Path f : files.toList()) {
                        String name = f.getFileName().toString();
                        if (!name.endsWith(".json")) continue;
                        out.add(new Location(unsegment(account.getFileName().toString()),
                                unsegment(name.substring(0, name.length() - 5))));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private Path root() {
        return dataDir.get().resolve(namespace.get()).resolve("mail-index");
    }

    private Path file(UserId user, Location location) {
        return root().resolve(segment(user.value())).resolve(segment(location.account()))
                .resolve(segment(location.folderId()) + ".json");
    }

    /** A user id, an account id or a folder id as one file name: anything goes in, nothing climbs out. */
    static String segment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String unsegment(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
