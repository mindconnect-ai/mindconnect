package ai.mindconnect.agent.tools.code;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The directories of the chat a container runs for — its working directory
 * and the additional ones — mounted writable under their own host paths, so a
 * path means the same in {@code bash}, the file tools, the Files dialog and
 * the code. The working directory is also {@code /workspace} and the
 * container's working directory.
 *
 * <p>Unlike {@link HostMount} these are not an operator's hole in the
 * isolation but the directories the session already works in, which the
 * other tools may write too. A directory that is gone, the file system root and
 * the container's own system trees ({@code /proc}, {@code /sys}, {@code /dev})
 * are left out: mounting over them would break the container, not open it.
 */
public record SessionDirs(Path workingDir, List<Path> additionalDirs) {

    /** Where the working directory also appears inside the container. */
    public static final String WORKSPACE = "/workspace";

    private static final List<String> SYSTEM_TREES = List.of("/proc", "/sys", "/dev");

    public SessionDirs {
        workingDir = usable(workingDir) ? workingDir.toAbsolutePath().normalize() : null;
        List<Path> kept = new ArrayList<>();
        for (Path dir : additionalDirs == null ? List.<Path>of() : additionalDirs) {
            if (!usable(dir)) continue;
            Path normalized = dir.toAbsolutePath().normalize();
            if (!normalized.equals(workingDir) && !kept.contains(normalized)) kept.add(normalized);
        }
        additionalDirs = List.copyOf(kept);
    }

    /** From the scope's strings; blank or missing ones are skipped. */
    public static SessionDirs of(String workingDir, Collection<String> additionalDirs) {
        List<Path> extras = new ArrayList<>();
        if (additionalDirs != null) {
            for (String dir : additionalDirs) {
                if (dir != null && !dir.isBlank()) extras.add(Path.of(dir));
            }
        }
        return new SessionDirs(workingDir == null || workingDir.isBlank() ? null : Path.of(workingDir), extras);
    }

    public static SessionDirs none() {
        return new SessionDirs(null, List.of());
    }

    public boolean isEmpty() {
        return workingDir == null && additionalDirs.isEmpty();
    }

    /** Every directory mounted under its host path: the working directory first. */
    public List<Path> all() {
        List<Path> all = new ArrayList<>();
        if (workingDir != null) all.add(workingDir);
        all.addAll(additionalDirs);
        return all;
    }

    /**
     * Part of the container's session key: mounts are fixed when a container
     * starts, so a chat that changed its directories needs a new one.
     */
    public String key() {
        return isEmpty() ? "-" : all().toString();
    }

    private static boolean usable(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        Path absolute = dir.toAbsolutePath().normalize();
        if (absolute.getParent() == null) return false;
        String value = absolute.toString().replace('\\', '/');
        return SYSTEM_TREES.stream().noneMatch(tree -> value.equals(tree) || value.startsWith(tree + "/"));
    }
}
