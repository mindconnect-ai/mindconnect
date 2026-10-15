package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceWalker;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A workspace that exists only in memory under {@code /workspace}: if a tool
 * still touched the local disk anywhere, it would find nothing there.
 */
class InMemoryWorkspaceFiles implements WorkspaceFiles {

    static final Path ROOT = Path.of("/workspace");

    private final Map<Path, byte[]> files = new TreeMap<>();
    private final Map<Path, Long> times = new TreeMap<>();
    private final AtomicLong clock = new AtomicLong(1_000);

    @Override
    public FileRoots roots() {
        return FileRoots.of(ROOT);
    }

    InMemoryWorkspaceFiles put(String relative, String content) {
        Path path = ROOT.resolve(relative);
        files.put(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        times.put(path, clock.incrementAndGet());
        return this;
    }

    String content(String relative) {
        byte[] bytes = files.get(ROOT.resolve(relative));
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Optional<WorkspaceEntry> stat(Path path) {
        if (files.containsKey(path)) {
            return Optional.of(new WorkspaceEntry(path, false, true, files.get(path).length, times.get(path)));
        }
        boolean directory = path.equals(ROOT) || files.keySet().stream().anyMatch(p -> p.startsWith(path));
        return directory ? Optional.of(new WorkspaceEntry(path, true, false, 0, 0)) : Optional.empty();
    }

    @Override
    public List<WorkspaceEntry> list(Path directory) throws IOException {
        if (!isDirectory(directory)) {
            throw new NoSuchFileException(directory.toString());
        }
        List<WorkspaceEntry> entries = new ArrayList<>();
        files.keySet().stream()
                .filter(p -> p.startsWith(directory) && !p.equals(directory))
                .map(p -> directory.resolve(directory.relativize(p).getName(0)))
                .distinct()
                .forEach(child -> stat(child).ifPresent(entries::add));
        return entries;
    }

    @Override
    public void walk(Path start, Set<String> excluded, WorkspaceWalker walker) throws IOException {
        walkFrom(start, excluded, walker, true);
    }

    private boolean walkFrom(Path dir, Set<String> excluded, WorkspaceWalker walker, boolean isStart)
            throws IOException {
        WorkspaceEntry entry = stat(dir).orElseThrow();
        if (!isStart && excluded.contains(entry.name())) {
            return true;
        }
        WorkspaceWalker.Step step = walker.directory(entry);
        if (step == WorkspaceWalker.Step.TERMINATE) return false;
        if (step == WorkspaceWalker.Step.SKIP_SUBTREE) return true;
        for (WorkspaceEntry child : list(dir)) {
            if (child.directory()) {
                if (!walkFrom(child.path(), excluded, walker, false)) return false;
            } else if (walker.file(child) == WorkspaceWalker.Step.TERMINATE) {
                return false;
            }
        }
        return true;
    }

    @Override
    public byte[] readAllBytes(Path file) throws IOException {
        byte[] bytes = files.get(file);
        if (bytes == null) throw new NoSuchFileException(file.toString());
        return bytes.clone();
    }

    @Override
    public byte[] readHead(Path file, int maxBytes) throws IOException {
        byte[] bytes = readAllBytes(file);
        return Arrays.copyOf(bytes, Math.min(bytes.length, maxBytes));
    }

    @Override
    public void write(Path file, byte[] content) {
        files.put(file, content.clone());
        times.put(file, clock.incrementAndGet());
    }

    @Override
    public LocalFile localFile(Path file) {
        throw new UnsupportedOperationException("no local files in memory");
    }
}
