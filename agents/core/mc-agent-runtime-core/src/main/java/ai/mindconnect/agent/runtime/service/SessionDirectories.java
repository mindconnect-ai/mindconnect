package ai.mindconnect.agent.runtime.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a user may look at of a session's directories: the working directory,
 * the additional ones and the session's own — whatever the agent wrote there
 * with {@code bash} or {@code code_execute}, for a file explorer to list and
 * hand out.
 *
 * <p>Read-only, and bounded by the roots: a path is resolved against one of
 * them and must stay inside it once symbolic links are followed, so neither
 * {@code ..} nor a link the agent planted reaches anything else on the host.
 */
public class SessionDirectories {

    /** A listing stops here; a directory with more entries says it was cut. */
    public static final int MAX_ENTRIES = 1000;

    private final List<Path> roots;

    /** @param roots the session's directories, in the order they are shown; ones that do not exist are left out */
    public SessionDirectories(List<Path> roots) {
        List<Path> existing = new ArrayList<>();
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root) && !existing.contains(root)) existing.add(root);
        }
        this.roots = List.copyOf(existing);
    }

    /** One entry of a directory. {@code path} is relative to the root, with forward slashes. */
    public record Entry(String name, String path, boolean directory, long size, Instant modified) {}

    /** A directory's entries — folders first, then by name — and whether {@link #MAX_ENTRIES} cut it. */
    public record Listing(Path root, String path, List<Entry> entries, boolean truncated) {}

    public List<Path> roots() {
        return roots;
    }

    /**
     * Lists {@code path} (relative, empty for the root itself) under {@code root}.
     * Empty when the root is not one of the session's or the path leaves it or is no directory.
     */
    public Optional<Listing> list(String root, String path) {
        return resolve(root, path).filter(Files::isDirectory).flatMap(dir -> {
            Path base = rootOf(root).orElseThrow();
            Path realRoot;
            try {
                realRoot = base.toRealPath();
            } catch (IOException e) {
                return Optional.empty();
            }
            String dirPath = realRoot.relativize(dir).toString().replace('\\', '/');
            List<Entry> entries = new ArrayList<>();
            boolean truncated = false;
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                    if (entries.size() == MAX_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    entry(realRoot, dirPath, child).ifPresent(entries::add);
                }
            } catch (IOException e) {
                return Optional.of(new Listing(base, dirPath, List.of(), false));
            }
            entries.sort(Comparator.comparing((Entry e) -> !e.directory())
                    .thenComparing(e -> e.name().toLowerCase()));
            return Optional.of(new Listing(base, dirPath, List.copyOf(entries), truncated));
        });
    }

    /** The regular file at {@code path} under {@code root}, when it is one and stays inside. */
    public Optional<Path> file(String root, String path) {
        return resolve(root, path).filter(Files::isRegularFile);
    }

    /**
     * {@code path} under {@code root}, real (links followed) and still inside
     * the root's real path. The root is matched against the session's roots by
     * its recorded string, never taken from the caller as a path to open.
     */
    private Optional<Path> resolve(String root, String path) {
        Optional<Path> base = rootOf(root);
        if (base.isEmpty()) return Optional.empty();
        try {
            Path realRoot = base.get().toRealPath();
            String rel = path == null ? "" : path.replace('\\', '/');
            while (rel.startsWith("/")) rel = rel.substring(1);
            Path candidate = realRoot.resolve(rel).normalize();
            if (!candidate.startsWith(realRoot) || !Files.exists(candidate)) return Optional.empty();
            Path real = candidate.toRealPath();
            return real.startsWith(realRoot) ? Optional.of(real) : Optional.empty();
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> rootOf(String root) {
        if (root == null) return Optional.empty();
        return roots.stream().filter(r -> r.toString().equals(root)).findFirst();
    }

    /** A child as an entry; a link pointing out of the root is not listed at all. */
    private static Optional<Entry> entry(Path realRoot, String dirPath, Path child) {
        try {
            Path real = child.toRealPath();
            if (!real.startsWith(realRoot)) return Optional.empty();
            BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String name = child.getFileName().toString();
            String path = dirPath.isEmpty() ? name : dirPath + "/" + name;
            return Optional.of(new Entry(name, path, attrs.isDirectory(),
                    attrs.isDirectory() ? 0 : attrs.size(), attrs.lastModifiedTime().toInstant()));
        } catch (IOException e) {
            // a dangling link, or one we may not read: nothing to show
            return Optional.empty();
        }
    }
}
