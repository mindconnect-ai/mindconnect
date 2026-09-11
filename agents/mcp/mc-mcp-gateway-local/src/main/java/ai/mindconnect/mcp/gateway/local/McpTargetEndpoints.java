package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.common.util.EnvVarResolver;
import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.proxy.DockerSpawnBuilder;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpHttpEndpoint;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a {@link McpTarget} — how an operator described a server — into
 * the {@link McpEndpoint} the proxy connects with. The one place that knows
 * both vocabularies.
 */
final class McpTargetEndpoints {

    /** Container CLI to run {@link McpTarget.Docker} with; null when none was found. */
    private final String containerBinary;

    McpTargetEndpoints(String containerBinary) {
        this.containerBinary = containerBinary;
    }

    /**
     * @throws McpGatewayException when the target cannot be run here at all —
     *         no container runtime, or a mount whose host path is missing.
     *         Both are configuration problems, and both are worth saying out
     *         loud rather than letting the container fail cryptically.
     */
    McpEndpoint toEndpoint(McpTarget target) {
        return switch (target) {
            case McpTarget.Docker docker -> dockerSpawn(docker);
            case McpTarget.Process process -> processSpawn(process);
            case McpTarget.Http http ->
                    new McpHttpEndpoint(http.url(), resolve(http.headers(), "header"), null, null);
        };
    }

    private McpStdioSpawn dockerSpawn(McpTarget.Docker docker) {
        if (containerBinary == null) {
            throw new McpGatewayException(
                    "no container runtime available for image '" + docker.image() + "'");
        }
        DockerSpawnBuilder builder = DockerSpawnBuilder.of(containerBinary, docker.image());
        for (McpTarget.Mount mount : docker.mounts()) {
            Path host = expandHome(mount.hostPath());
            if (!Files.exists(host)) {
                throw new McpGatewayException(
                        "mount source does not exist: " + host + " (for image '" + docker.image() + "')");
            }
            builder.mount(host.toString(), mount.containerPath());
        }
        for (Map.Entry<String, String> e : resolve(docker.env(), "environment variable").entrySet()) {
            builder.env(e.getKey(), e.getValue());
        }
        for (String flag : docker.runFlags()) {
            builder.dockerFlag(flag);
        }
        if (!docker.command().isEmpty()) {
            builder.commandOverride(docker.command());
        }
        return builder.build();
    }

    private McpStdioSpawn processSpawn(McpTarget.Process process) {
        String executable = process.command().get(0);
        return new McpStdioSpawn(
                executable,
                process.command().subList(1, process.command().size()),
                resolve(process.env(), "environment variable"),
                null,
                null);
    }

    /**
     * Expands {@code ${VAR}} / {@code ${VAR:default}} in the <em>values</em> of
     * an env or header map, so a registration can name where a secret lives
     * instead of carrying it.
     *
     * <p>Here and nowhere else. This is the last moment before the value is
     * handed to the transport, so the secret never reaches the registration
     * file, the discovery cache, a form or a log — which is the whole point
     * of the indirection.
     *
     * <p>Values only, never keys, and only these two maps: the image, the
     * command and the URL stay literal. A registration is data, not a
     * template language — the same line {@link #expandHome} draws. The URL in
     * particular must stay literal because {@code McpTarget.Http} checks its
     * scheme when the registration is built, and a placeholder cannot be
     * checked.
     *
     * @throws McpGatewayException naming the field and the missing variable —
     *         never a value
     */
    private static Map<String, String> resolve(Map<String, String> values, String what) {
        if (values.isEmpty()) {
            return values;
        }
        // Ordered: env becomes -e flags, and a spawn should be the same
        // command line on every start.
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            try {
                resolved.put(e.getKey(), EnvVarResolver.resolve(e.getValue()));
            } catch (RuntimeException failure) {
                throw new McpGatewayException(
                        what + " '" + e.getKey() + "' cannot be resolved: " + failure.getMessage(),
                        failure);
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Expands a leading {@code ~/} to the user's home. Deliberately the only
     * substitution: a registration is data, and turning it into a template
     * language invites reading arbitrary system properties into a container.
     */
    static Path expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return Paths.get(home, path.length() == 1 ? "" : path.substring(2));
        }
        return Paths.get(path);
    }
}
