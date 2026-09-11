package ai.mindconnect.agent.tool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a file-rooted tool may go: one base directory — relative paths
 * resolve against it, {@code .} is it — and any number of additional
 * directories reachable by absolute path. A session's working directory
 * and its additional directories, or the configured default alone.
 *
 * <p>The one sandbox check every file tool shares: a path leaves
 * {@link #resolve} either inside one of the roots, normalised, or not at
 * all. {@code ~} and {@code $HOME} at the start of a path stand for the
 * user's home; an absolute path is taken as written, so a path into an
 * additional directory needs no relativising against the base.
 */
public record FileRoots(Path base, List<Path> extra) {

    public FileRoots {
        Path root = base.toAbsolutePath().normalize();
        base = root;
        extra = extra == null ? List.of() : extra.stream()
                .filter(p -> p != null)
                .map(p -> p.toAbsolutePath().normalize())
                .filter(p -> !p.equals(root))
                .distinct()
                .toList();
    }

    /** The base alone. */
    public static FileRoots of(Path base) {
        return new FileRoots(base, List.of());
    }

    /** The base and the additional directories, as strings ({@code null} and blank entries ignored). */
    public static FileRoots of(String base, List<String> extra) {
        List<Path> paths = new ArrayList<>();
        if (extra != null) {
            for (String e : extra) {
                if (e != null && !e.isBlank()) paths.add(Path.of(e.trim()));
            }
        }
        return new FileRoots(Path.of(base), paths);
    }

    /**
     * The absolute, normalised path {@code raw} names, when it lies inside
     * a root; empty when it does not, or is no path at all. A relative path
     * is relative to the base.
     */
    public Optional<Path> resolve(String raw) {
        if (raw == null) return Optional.empty();
        String s = expandHome(raw.trim());
        if (s.isEmpty()) s = ".";
        Path target;
        try {
            Path asPath = Path.of(s);
            target = (asPath.isAbsolute() ? asPath : base.resolve(asPath)).normalize();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        return contains(target) ? Optional.of(target) : Optional.empty();
    }

    /** Is {@code path} (absolute, normalised) inside one of the roots? */
    public boolean contains(Path path) {
        if (path.startsWith(base)) return true;
        for (Path root : extra) {
            if (path.startsWith(root)) return true;
        }
        return false;
    }

    /** {@code path} as a tool reports it: relative to the base when under it, absolute otherwise. */
    public String display(Path path) {
        if (path.startsWith(base)) {
            String rel = base.relativize(path).toString();
            return rel.isEmpty() ? "." : rel;
        }
        return path.toString();
    }

    /** "/home/me/app" or "/home/me/app (also /home/me/lib, /srv/data)". */
    public String describe() {
        if (extra.isEmpty()) return base.toString();
        StringBuilder out = new StringBuilder(base.toString()).append(" (also ");
        for (int i = 0; i < extra.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(extra.get(i));
        }
        return out.append(')').toString();
    }

    /** The error a tool returns for a path outside every root. */
    public String outsideError(String raw) {
        return "Error: path is outside the allowed directories (" + describe() + "). Requested: " + raw;
    }

    static String expandHome(String s) {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) return s;
        if (s.equals("~") || s.equals("$HOME") || s.equals("${HOME}")) return home;
        if (s.startsWith("~/")) return home + s.substring(1);
        if (s.startsWith("$HOME/")) return home + s.substring("$HOME".length());
        if (s.startsWith("${HOME}/")) return home + s.substring("${HOME}".length());
        return s;
    }
}
