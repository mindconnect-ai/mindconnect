package ai.mindconnect.mcp.proxy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a started MCP server inherits from the environment of this process:
 * what a command needs to run and to reach the network, and nothing else.
 *
 * <p>A child process inherits every variable of its parent unless told
 * otherwise. The parent here holds the database password, LLM keys and the
 * encryption secret; an MCP server is somebody else's code, registered through
 * a screen that asks for a login, and has no business seeing any of it. What a
 * server does need arrives through its registration's own {@code env}, which
 * is added after this.
 *
 * <p>Kept: finding the command, a home and a temp directory, locale and time
 * zone, proxies, and what the docker and podman CLIs read to find their
 * daemon. Names are compared ignoring case, because Windows spells {@code Path}
 * and {@code SystemRoot} as it pleases.
 */
final class InheritedEnvironment {

    private static final Set<String> KEPT = Set.of(
            // running a command at all
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TZ", "TMPDIR", "TMP", "TEMP",
            // Windows: without these a node or python process does not start
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
            // reaching the network from behind a proxy
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
            // where a user's tools keep their state
            "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME",
            // finding the container daemon
            "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY",
            "CONTAINER_HOST", "CONTAINER_SSHKEY", "CONTAINERS_CONF", "CONTAINERS_STORAGE_CONF");

    private InheritedEnvironment() {
    }

    /** Removes every variable a started server does not inherit. */
    static void trim(Map<String, String> environment) {
        environment.keySet().removeIf(name -> !kept(name));
    }

    static boolean kept(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return KEPT.contains(upper) || upper.startsWith("LC_");
    }
}
