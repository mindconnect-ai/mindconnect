package ai.mindconnect.agent.tool.workspace;

import ai.mindconnect.agent.tool.FileRoots;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link WorkspaceFiles} on this machine's file system: what the file tools
 * always did, moved behind the interface.
 */
public class LocalWorkspaceFiles implements WorkspaceFiles {

    private final FileRoots roots;

    public LocalWorkspaceFiles(FileRoots roots) {
        this.roots = roots;
    }

    @Override
    public FileRoots roots() {
        return roots;
    }

    @Override
    public Optional<WorkspaceEntry> stat(Path path) throws IOException {
        try {
            return Optional.of(entry(path, Files.readAttributes(path, BasicFileAttributes.class)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<WorkspaceEntry> list(Path directory) throws IOException {
        List<WorkspaceEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                try {
                    // Like Files.isDirectory: a link to a directory lists as one.
                    entries.add(entry(child, Files.readAttributes(child, BasicFileAttributes.class)));
                } catch (IOException e) {
                    entries.add(entry(child, Files.readAttributes(child, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS)));
                }
            }
        }
        return entries;
    }

    @Override
    public void walk(Path start, Set<String> excludedDirectoryNames, WorkspaceWalker walker) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(start) && excludedDirectoryNames.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return result(walker.directory(entry(dir, attrs)));
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return result(walker.file(entry(file, attrs)));
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public byte[] readAllBytes(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    @Override
    public byte[] readHead(Path file, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(maxBytes);
        }
    }

    @Override
    public void write(Path file, byte[] content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content);
    }

    @Override
    public void delete(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.deleteIfExists(path)) throw new NoSuchFileException(path.toString());
            return;
        }
        // A walk does not follow links: a link inside is removed, what it points to stays.
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public LocalFile localFile(Path file) {
        return new LocalFile() {
            @Override
            public Path path() {
                return file;
            }

            @Override
            public void close() {
            }
        };
    }

    private static WorkspaceEntry entry(Path path, BasicFileAttributes attrs) {
        return new WorkspaceEntry(path, attrs.isDirectory(), attrs.isRegularFile(),
                attrs.isRegularFile() ? attrs.size() : 0, attrs.lastModifiedTime().toMillis());
    }

    private static FileVisitResult result(WorkspaceWalker.Step step) {
        return switch (step) {
            case CONTINUE -> FileVisitResult.CONTINUE;
            case SKIP_SUBTREE -> FileVisitResult.SKIP_SUBTREE;
            case TERMINATE -> FileVisitResult.TERMINATE;
        };
    }
}
