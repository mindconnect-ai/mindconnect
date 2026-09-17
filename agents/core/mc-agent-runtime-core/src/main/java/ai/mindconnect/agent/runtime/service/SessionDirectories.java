package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a user may look at of a session's directories: the working directory,
 * the additional ones and the session's own — whatever the agent wrote there
 * with {@code bash} or {@code code_execute}, for a file explorer to list, hand
 * out, edit as text and delete.
 *
 * <p>Bounded by the roots: a path is resolved against one of
 * them and must stay inside it once symbolic links are followed, so neither
 * {@code ..} nor a link the agent planted reaches anything else on the host.
 */
public class SessionDirectories {

    /** A listing stops here; a directory with more entries says it was cut. */
    public static final int MAX_ENTRIES = 1000;

    /** A folder with more files than this is not packed as a zip. */
    public static final int MAX_ARCHIVE_FILES = 10_000;
    /** A folder whose files add up to more than this is not packed as a zip. */
    public static final long MAX_ARCHIVE_BYTES = 1L << 30;

    private final List<Path> roots;
    /** Roots that live elsewhere — a virtual environment's workspace — by the name they are shown under. */
    private final Map<String, WorkspaceFiles> workspaces;

    /** Names in a workspace root that are the environment's own, not the session's files. */
    private static final Set<String> WORKSPACE_INTERNALS = Set.of(".home", ".mc");

    /** @param roots the session's directories, in the order they are shown; ones that do not exist are left out */
    public SessionDirectories(List<Path> roots) {
        this(roots, Map.of());
    }

    /**
     * @param workspaces workspaces that live elsewhere, shown first under their key (e.g. {@code /workspace});
     *                   read through {@link WorkspaceFiles}, which does its own confinement
     */
    public SessionDirectories(List<Path> roots, Map<String, WorkspaceFiles> workspaces) {
        List<Path> existing = new ArrayList<>();
        workspaces.keySet().stream().sorted().map(Path::of).forEach(existing::add);
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root) && !existing.contains(root)) existing.add(root);
        }
        this.roots = List.copyOf(existing);
        this.workspaces = Map.copyOf(workspaces);
    }

    /** A file's name, size and bytes, for viewing or downloading. */
    public record Content(String name, long size, InputStream stream) {}

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
        if (root != null && workspaces.containsKey(root)) {
            return listWorkspace(root, path);
        }
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

    /** The file at {@code path} under {@code root} to read, local or in a workspace elsewhere. */
    public Optional<Content> open(String root, String path) throws IOException {
        WorkspaceFiles remote = root == null ? null : workspaces.get(root);
        if (remote != null) {
            Optional<Path> target = workspacePath(remote, path);
            if (target.isEmpty() || !remote.isRegularFile(target.get())) return Optional.empty();
            byte[] bytes = remote.readAllBytes(target.get());
            return Optional.of(new Content(target.get().getFileName().toString(), bytes.length,
                    new ByteArrayInputStream(bytes)));
        }
        Optional<Path> file = file(root, path);
        if (file.isEmpty()) return Optional.empty();
        return Optional.of(new Content(file.get().getFileName().toString(), Files.size(file.get()),
                Files.newInputStream(file.get())));
    }

    /** A file shown as its text, when it is UTF-8 text of at most this many bytes. */
    public static final int MAX_EDIT_BYTES = 1024 * 1024;

    /** A file as a viewer shows it: its entry, and its text when it can be edited as such ({@code null} otherwise). */
    public record Preview(Entry entry, String text) {
        public boolean editable() {
            return text != null;
        }
    }

    /** The file at {@code path} under {@code root} for a viewer; empty when it is no file of the session's. */
    public Optional<Preview> preview(String root, String path) throws IOException {
        String rel = relative(path);
        WorkspaceFiles remote = root == null ? null : workspaces.get(root);
        if (remote != null) {
            Optional<Path> target = workspacePath(remote, rel);
            if (target.isEmpty() || internal(rel)) return Optional.empty();
            Optional<WorkspaceEntry> stat = remote.stat(target.get());
            if (stat.isEmpty() || !stat.get().regularFile()) return Optional.empty();
            Entry entry = new Entry(stat.get().name(), rel, false, stat.get().size(),
                    Instant.ofEpochMilli(stat.get().lastModifiedMillis()));
            return Optional.of(new Preview(entry, text(remote.readHead(target.get(), MAX_EDIT_BYTES + 1))));
        }
        Optional<Path> file = file(root, rel);
        if (file.isEmpty()) return Optional.empty();
        BasicFileAttributes attrs = Files.readAttributes(file.get(), BasicFileAttributes.class);
        Entry entry = new Entry(name(rel), rel, false, attrs.size(), attrs.lastModifiedTime().toInstant());
        byte[] head;
        try (InputStream in = Files.newInputStream(file.get())) {
            head = in.readNBytes(MAX_EDIT_BYTES + 1);
        }
        return Optional.of(new Preview(entry, text(head)));
    }

    /**
     * Replaces the text of an existing file at {@code path} under {@code root}; no file is
     * created. Returns the file's entry afterwards, empty when it is no file of the session's.
     *
     * @throws IllegalArgumentException when the text is larger than {@link #MAX_EDIT_BYTES}
     */
    public Optional<Entry> write(String root, String path, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDIT_BYTES) {
            throw new IllegalArgumentException("The text is larger than " + MAX_EDIT_BYTES + " bytes");
        }
        String rel = relative(path);
        WorkspaceFiles remote = root == null ? null : workspaces.get(root);
        if (remote != null) {
            Optional<Path> target = workspacePath(remote, rel);
            if (target.isEmpty() || internal(rel) || !remote.isRegularFile(target.get())) return Optional.empty();
            remote.write(target.get(), bytes);
        } else {
            Optional<Path> file = file(root, rel);
            if (file.isEmpty()) return Optional.empty();
            Files.write(file.get(), bytes);
        }
        return preview(root, rel).map(Preview::entry);
    }

    /**
     * Deletes the file or folder at {@code path} under {@code root}, a folder with everything
     * in it. A link is removed itself, never what it points to. A root is never deleted.
     *
     * @return whether there was something of the session's to delete
     */
    public boolean delete(String root, String path) throws IOException {
        String rel = relative(path);
        if (rel.isEmpty()) return false;
        WorkspaceFiles remote = root == null ? null : workspaces.get(root);
        if (remote != null) {
            Optional<Path> target = workspacePath(remote, rel);
            if (target.isEmpty() || internal(rel) || target.get().equals(remote.roots().base())
                    || !remote.exists(target.get())) {
                return false;
            }
            remote.delete(target.get());
            return true;
        }
        Optional<Path> base = rootOf(root);
        if (base.isEmpty()) return false;
        try {
            Path realRoot = base.get().toRealPath();
            // The entry itself, links not followed: its parent must be inside, it need not point inside.
            Path candidate = realRoot.resolve(rel).normalize();
            if (candidate.equals(realRoot) || !candidate.startsWith(realRoot)
                    || !candidate.getParent().toRealPath().startsWith(realRoot)
                    || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            WorkspaceFiles.local(ai.mindconnect.agent.tool.FileRoots.of(realRoot)).delete(candidate);
            return true;
        } catch (InvalidPathException | java.nio.file.NoSuchFileException e) {
            return false;
        }
    }

    /** {@code path} as a relative path with forward slashes, {@code ..} and {@code .} folded; empty for a root. */
    private static String relative(String path) {
        String rel = path == null ? "" : path.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        String normalized = Path.of(rel).normalize().toString().replace('\\', '/');
        return normalized.equals(".") ? "" : normalized;
    }

    /** Whether a workspace path lies in the environment's own folders. */
    private static boolean internal(String rel) {
        int slash = rel.indexOf('/');
        return WORKSPACE_INTERNALS.contains(slash < 0 ? rel : rel.substring(0, slash));
    }

    private static String name(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    /** The bytes as text when they are UTF-8 without NUL and fit {@link #MAX_EDIT_BYTES}; else null. */
    private static String text(byte[] bytes) {
        if (bytes.length > MAX_EDIT_BYTES) return null;
        for (byte b : bytes) {
            if (b == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Opens one member's bytes when the archive is written. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    /** One entry of an archive: {@code path} inside the zip, with forward slashes; a folder has no source and ends in {@code /}. */
    public record Member(String path, long size, Source source) {
        public boolean directory() {
            return source == null;
        }
    }

    /**
     * A folder packed as a zip, planned but not yet written: its file name, what goes in
     * and how much. {@code tooLarge} says the folder passed {@link #MAX_ARCHIVE_FILES} or
     * {@link #MAX_ARCHIVE_BYTES}; the members then stop there and it should not be handed out.
     */
    public record Archive(String name, List<Member> members, int files, long bytes, boolean tooLarge) {

        /** Writes the zip; the stream is finished, not closed. */
        public void writeTo(OutputStream out) throws IOException {
            ZipOutputStream zip = new ZipOutputStream(out);
            for (Member member : members) {
                zip.putNextEntry(new ZipEntry(member.path()));
                if (!member.directory()) {
                    try (InputStream in = member.source().open()) {
                        in.transferTo(zip);
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
        }
    }

    /** {@link #archive(String, String, String)} named after the folder itself. */
    public Optional<Archive> archive(String root, String path) {
        return archive(root, path, null);
    }

    /**
     * The folder at {@code path} under {@code root} (empty for the root itself) as a zip,
     * with everything inside one top-level folder. Only what the folder's listing would
     * show goes in: a link out of the root is left out, and a linked folder is not
     * descended into, so a link cannot loop. Empty when the folder is not one of the
     * session's or leaves it.
     *
     * @param name what the zip and its top-level folder are called; the folder's own name when null
     */
    public Optional<Archive> archive(String root, String path, String name) {
        WorkspaceFiles remote = root == null ? null : workspaces.get(root);
        if (remote != null) {
            Optional<Path> dir = workspacePath(remote, path);
            if (dir.isEmpty() || !remote.isDirectory(dir.get())) return Optional.empty();
            boolean atRoot = dir.get().equals(remote.roots().base());
            var packer = new Packer(archiveName(name, root, atRoot ? Path.of(root) : dir.get()));
            try {
                packRemote(remote, dir.get(), packer.top, atRoot, packer);
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.of(packer.archive());
        }
        return resolve(root, path).filter(Files::isDirectory).flatMap(dir -> {
            Path realRoot;
            try {
                realRoot = rootOf(root).orElseThrow().toRealPath();
            } catch (IOException e) {
                return Optional.empty();
            }
            var packer = new Packer(archiveName(name, root, dir.equals(realRoot) ? Path.of(root) : dir));
            try {
                packLocal(realRoot, dir, packer.top, packer);
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.of(packer.archive());
        });
    }

    private static void packLocal(Path realRoot, Path dir, String prefix, Packer packer) throws IOException {
        packer.folder(prefix);
        List<Path> children;
        try (Stream<Path> listed = Files.list(dir)) {
            children = listed.sorted().toList();
        }
        for (Path child : children) {
            if (packer.tooLarge) return;
            Path real;
            try {
                real = child.toRealPath();
            } catch (IOException e) {
                continue;   // a dangling link, or one we may not read
            }
            if (!real.startsWith(realRoot)) continue;
            String name = prefix + child.getFileName();
            if (Files.isDirectory(real)) {
                if (!Files.isSymbolicLink(child)) packLocal(realRoot, real, name + "/", packer);
            } else if (Files.isRegularFile(real)) {
                packer.file(name, Files.size(real), () -> Files.newInputStream(real));
            }
        }
    }

    private static void packRemote(WorkspaceFiles remote, Path dir, String prefix, boolean atRoot, Packer packer)
            throws IOException {
        packer.folder(prefix);
        List<WorkspaceEntry> children = remote.list(dir).stream()
                .sorted(Comparator.comparing(WorkspaceEntry::name)).toList();
        for (WorkspaceEntry child : children) {
            if (packer.tooLarge) return;
            if (atRoot && WORKSPACE_INTERNALS.contains(child.name())) continue;
            String name = prefix + child.name();
            if (child.directory()) {
                packRemote(remote, child.path(), name + "/", false, packer);
            } else if (child.regularFile()) {
                Path file = child.path();
                packer.file(name, child.size(), () -> new ByteArrayInputStream(remote.readAllBytes(file)));
            }
        }
    }

    /** A name fit for a file: the one given, else the folder's own. */
    private static String archiveName(String name, String root, Path dir) {
        String folder = name;
        if (folder == null || folder.isBlank()) {
            Path last = dir.getFileName();
            folder = last == null ? root : last.toString();
        }
        String safe = folder.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "files" : safe;
    }

    /** Collects an archive's members until a limit is reached. */
    private static class Packer {
        final String top;
        final List<Member> members = new ArrayList<>();
        int files;
        long bytes;
        boolean tooLarge;

        Packer(String name) {
            this.top = name + "/";
        }

        void folder(String path) {
            if (!tooLarge) members.add(new Member(path, 0, null));
        }

        void file(String path, long size, Source source) {
            if (files + 1 > MAX_ARCHIVE_FILES || bytes + size > MAX_ARCHIVE_BYTES) {
                tooLarge = true;
                return;
            }
            files++;
            bytes += size;
            members.add(new Member(path, size, source));
        }

        Archive archive() {
            return new Archive(top.substring(0, top.length() - 1) + ".zip", List.copyOf(members), files, bytes, tooLarge);
        }
    }

    private Optional<Listing> listWorkspace(String root, String path) {
        WorkspaceFiles remote = workspaces.get(root);
        Optional<Path> dir = workspacePath(remote, path);
        if (dir.isEmpty() || !remote.isDirectory(dir.get())) return Optional.empty();
        Path base = remote.roots().base();
        String dirPath = base.relativize(dir.get()).toString().replace('\\', '/');
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try {
            for (WorkspaceEntry child : remote.list(dir.get())) {
                boolean internal = dirPath.isEmpty() && WORKSPACE_INTERNALS.contains(child.name());
                if (internal || !(child.directory() || child.regularFile())) continue;   // links are not followed
                if (entries.size() == MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                String childPath = dirPath.isEmpty() ? child.name() : dirPath + "/" + child.name();
                entries.add(new Entry(child.name(), childPath, child.directory(), child.directory() ? 0 : child.size(),
                        Instant.ofEpochMilli(child.lastModifiedMillis())));
            }
        } catch (IOException e) {
            return Optional.of(new Listing(Path.of(root), dirPath, List.of(), false));
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.directory())
                .thenComparing(e -> e.name().toLowerCase()));
        return Optional.of(new Listing(Path.of(root), dirPath, List.copyOf(entries), truncated));
    }

    /** {@code path} in a workspace, when its roots accept it. */
    private static Optional<Path> workspacePath(WorkspaceFiles remote, String path) {
        String rel = path == null ? "" : path.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        return remote.roots().resolve(rel.isEmpty() ? "." : rel);
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
