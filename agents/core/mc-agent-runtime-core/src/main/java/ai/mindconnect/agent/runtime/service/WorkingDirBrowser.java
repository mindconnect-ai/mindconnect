package ai.mindconnect.agent.runtime.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The directories a user may pick a working directory from — what a picker
 * in the chat shows. Browses the same tree the {@link WorkingDirPolicy}
 * allows and nothing beyond it: the listing starts at the policy's root
 * (the user's own, on a per-user root) and every step down is validated
 * like a {@code /cd}. An unrestricted policy — the CLI — starts at the
 * user's home.
 *
 * <p>Only directories, none of them hidden, sorted by name, at most
 * {@link #MAX_ENTRIES} of them; a bigger directory is cut and says so.
 */
public final class WorkingDirBrowser {

    /** More sub-directories than this are cut off — a picker, not a file manager. */
    public static final int MAX_ENTRIES = 200;

    private final WorkingDirPolicy policy;

    public WorkingDirBrowser(WorkingDirPolicy policy) {
        this.policy = policy == null ? WorkingDirPolicy.unrestricted() : policy;
    }

    /**
     * One level of the tree.
     *
     * @param root      where browsing starts and may not leave ({@code null} when unrestricted)
     * @param current   the directory listed, absolute
     * @param parent    its parent, or {@code null} at the root
     * @param subdirs   the sub-directories, absolute, sorted
     * @param truncated whether more than {@link #MAX_ENTRIES} were cut off
     */
    public record Listing(String root, String current, String parent, List<String> subdirs, boolean truncated) {}

    /**
     * The sub-directories of {@code path} for {@code userId} — of the root
     * when {@code path} is {@code null} or blank. An
     * {@link IllegalArgumentException} names a path that is no directory or
     * lies outside the root.
     */
    public Listing list(String userId, String path) {
        WorkingDirPolicy user = policy.forUser(userId);
        // The real root: validate() answers real paths, and the way up is found by comparing against it.
        Path root = user.realRoot();
        Path start = root != null ? root : Path.of(System.getProperty("user.home"));
        String current = path == null || path.isBlank() ? start.toString() : user.validate(path);
        Path dir = Path.of(current);
        Path parent = dir.getParent();
        String parentOrNull = parent == null || (root != null && !dir.startsWith(root)) || dir.equals(root)
                ? null : parent.toString();

        List<String> subdirs = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> dirs = entries
                    .filter(p -> Files.isDirectory(p) && !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
            for (Path d : dirs) {
                if (subdirs.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                subdirs.add(d.toString());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot list " + dir + ": " + e.getMessage());
        }
        return new Listing(root == null ? null : root.toString(), current, parentOrNull, List.copyOf(subdirs), truncated);
    }

    /** Same as {@link #list(String, String)}, for a typed user id. */
    public Listing list(ai.mindconnect.agent.UserId userId, String path) {
        return list(userId == null ? null : userId.value(), path);
    }
}
