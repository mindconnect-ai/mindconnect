package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A session's workspace on the virtual environment server, seen by the file
 * tools as {@code /workspace} — the path it has inside the container, so a path
 * {@code file_read} shows works unchanged in {@code bash}.
 *
 * <p>A walk is one call: the server lists the tree, excluded directories pruned
 * on its side, and the walker gets the entries replayed.
 */
public class RemoteWorkspaceFiles implements WorkspaceFiles {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkspaceFiles.class);

    /** Where the workspace is mounted in the container, and the root the tools see. */
    public static final Path ROOT = Path.of("/workspace");
    static final int MAX_TREE_ENTRIES = 20_000;

    private final VirtualEnvClient client;
    private final WorkspaceKey key;
    private final FileRoots roots;

    public RemoteWorkspaceFiles(VirtualEnvClient client, WorkspaceKey key) {
        this(client, key, null);
    }

    /**
     * @param localAlias the session directory on this machine that the prompt names, read as
     *                   {@code /workspace}; {@code null} when the session has none
     */
    public RemoteWorkspaceFiles(VirtualEnvClient client, WorkspaceKey key, Path localAlias) {
        this.client = client;
        this.key = key;
        this.roots = localAlias == null ? FileRoots.of(ROOT) : FileRoots.of(ROOT).withAlias(localAlias);
    }

    @Override
    public FileRoots roots() {
        return roots;
    }

    @Override
    public String identity() {
        return client.baseUrl() + "/" + key.id();
    }

    @Override
    public Optional<WorkspaceEntry> stat(Path path) throws IOException {
        return client.stat(key, relative(path)).map(RemoteWorkspaceFiles::entry);
    }

    @Override
    public List<WorkspaceEntry> list(Path directory) throws IOException {
        return client.list(key, relative(directory)).stream().map(RemoteWorkspaceFiles::entry).toList();
    }

    @Override
    public void walk(Path start, Set<String> excludedDirectoryNames, WorkspaceWalker walker) throws IOException {
        VirtualEnvClient.Tree tree = client.tree(key, relative(start), excludedDirectoryNames, MAX_TREE_ENTRIES);
        if (tree.truncated()) {
            log.warn("Workspace tree below {} has more than {} entries; the walk sees the first ones only",
                    start, MAX_TREE_ENTRIES);
        }
        Path skipping = null;
        for (VirtualEnvClient.Entry remote : tree.entries()) {
            WorkspaceEntry entry = entry(remote);
            if (skipping != null && entry.path().startsWith(skipping)) {
                continue;
            }
            skipping = null;
            WorkspaceWalker.Step step = entry.directory() ? walker.directory(entry) : walker.file(entry);
            if (step == WorkspaceWalker.Step.TERMINATE) {
                return;
            }
            if (step == WorkspaceWalker.Step.SKIP_SUBTREE && entry.directory()) {
                skipping = entry.path();
            }
        }
    }

    @Override
    public byte[] readAllBytes(Path file) throws IOException {
        return read(file, null);
    }

    @Override
    public byte[] readHead(Path file, int maxBytes) throws IOException {
        return read(file, maxBytes);
    }

    @Override
    public void write(Path file, byte[] content) throws IOException {
        client.write(key, relative(file), content);
    }

    @Override
    public LocalFile localFile(Path file) throws IOException {
        String name = file.getFileName() == null ? "file" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path copy = Files.createTempFile("mc-workspace-", dot > 0 ? name.substring(dot) : ".tmp");
        try {
            Files.write(copy, readAllBytes(file));
        } catch (IOException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        return new LocalFile() {
            @Override
            public Path path() {
                return copy;
            }

            @Override
            public void close() {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException e) {
                    log.debug("Could not delete temporary copy {}", copy);
                }
            }
        };
    }

    private byte[] read(Path file, Integer maxBytes) throws IOException {
        try {
            return client.read(key, relative(file), maxBytes);
        } catch (VirtualEnvClientException e) {
            if (e.status() == 404) {
                throw new NoSuchFileException(file.toString());
            }
            throw e;
        }
    }

    /** {@code /workspace/out/deck.pptx} → {@code out/deck.pptx}; the root is the empty string. */
    static String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(ROOT)) {
            throw new IllegalArgumentException("Not in the workspace: " + path);
        }
        return ROOT.relativize(normalized).toString().replace('\\', '/');
    }

    static WorkspaceEntry entry(VirtualEnvClient.Entry remote) {
        Path path = remote.path() == null || remote.path().isEmpty() ? ROOT : ROOT.resolve(remote.path());
        long modified = remote.modifiedAt() == null ? 0 : Instant.parse(remote.modifiedAt()).toEpochMilli();
        return new WorkspaceEntry(path, remote.directory(), remote.regularFile(), remote.size(), modified);
    }
}
