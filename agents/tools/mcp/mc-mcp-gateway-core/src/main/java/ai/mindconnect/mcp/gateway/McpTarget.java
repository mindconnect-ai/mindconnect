package ai.mindconnect.mcp.gateway;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How one MCP server is reached. Sealed, because transport and start-up are
 * not independent in practice: a container or a process speaks stdio, a
 * remote endpoint speaks HTTP. Making the combination a single type means
 * an invalid one cannot be built (concept 21 §3).
 *
 */
public sealed interface McpTarget {

    /**
     * A container image run by a docker-compatible CLI, talking JSON-RPC
     * over stdio.
     *
     * @param image       image reference, e.g. {@code mcp/gmail:latest}
     * @param mounts      host→container bind mounts
     * @param env         environment for the process INSIDE the container
     * @param runFlags    extra flags for {@code run}, e.g. {@code --memory=512m}
     * @param command     overrides the image's entrypoint when non-empty
     */
    record Docker(
            String image,
            List<Mount> mounts,
            Map<String, String> env,
            List<String> runFlags,
            List<String> command
    ) implements McpTarget {
        public Docker {
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("image required");
            }
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
            // Ordered: env becomes -e flags, and a spawn should be the same
            // command line on every start.
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
            runFlags = runFlags == null ? List.of() : List.copyOf(runFlags);
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    /**
     * A plain local process talking JSON-RPC over stdio. No isolation
     * whatsoever — see concept 21 §9.1 for who may register one.
     *
     * <p>No working directory: the proxy's spawn has none, and a field that
     * is silently ignored is worse than a missing one. It arrives when
     * {@code McpStdioSpawn} grows one.
     *
     * @param command  executable plus arguments
     * @param env      environment for the process
     */
    record Process(
            List<String> command,
            Map<String, String> env
    ) implements McpTarget {
        public Process {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("command required");
            }
            command = List.copyOf(command);
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    /**
     * A server somebody else runs, reached over streamable HTTP.
     *
     * <p>{@code headers} carry whatever the endpoint wants for
     * authentication. They are values, not references to a credential
     * store — that store does not exist yet (concept 21 §8), so a token
     * pasted here is stored as it was typed. Which is why {@code http} is
     * refused for a non-local host: an unencrypted hop would put it on the
     * wire in clear.
     *
     * @param url      full endpoint URL, e.g. {@code https://mcp.example.com/mcp}
     * @param headers  sent with every request
     */
    record Http(URI url, Map<String, String> headers) implements McpTarget {
        public Http {
            if (url == null) {
                throw new IllegalArgumentException("url required");
            }
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && isLocal(url.getHost()))) {
                throw new IllegalArgumentException(
                        "url must be https (http is allowed for localhost only): " + url);
            }
            headers = headers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        private static boolean isLocal(String host) {
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("[::1]") || host.endsWith(".localhost"));
        }
    }

    /** One bind mount of a {@link Docker} target. */
    record Mount(String hostPath, String containerPath) {
        public Mount {
            if (hostPath == null || hostPath.isBlank()) {
                throw new IllegalArgumentException("hostPath required");
            }
            if (containerPath == null || containerPath.isBlank()) {
                throw new IllegalArgumentException("containerPath required");
            }
        }
    }
}
