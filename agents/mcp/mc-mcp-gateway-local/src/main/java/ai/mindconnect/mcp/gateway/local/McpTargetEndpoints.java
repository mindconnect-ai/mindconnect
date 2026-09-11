package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.proxy.DockerSpawnBuilder;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpHttpEndpoint;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a {@link McpTarget} — how an operator described a server — into
 * the {@link McpEndpoint} the proxy connects with. The one place that knows
 * both vocabularies.
 */
final class McpTargetEndpoints {

    /** {@code ${NAME}} or {@code ${NAME:default}} — the name is group 1. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::[^}]*)?}");

    /** Container CLI to run {@link McpTarget.Docker} with; null when none was found. */
    private final String containerBinary;

    McpTargetEndpoints(String containerBinary) {
        this.containerBinary = containerBinary;
    }

    /**
     * @throws McpGatewayException when the target cannot be run here at all —
     *         no container runtime, a mount whose host path is missing, or a
     *         value that uses a variable. All are configuration problems, and
     *         all are worth saying out loud rather than letting the server fail
     *         cryptically.
     */
    McpEndpoint toEndpoint(McpTarget target) {
        return switch (target) {
            case McpTarget.Docker docker -> dockerSpawn(docker);
            case McpTarget.Process process -> processSpawn(process);
            case McpTarget.Http http ->
                    new McpHttpEndpoint(http.url(), literal(http.headers(), "header"), null, null);
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
        for (Map.Entry<String, String> e : literal(docker.env(), "environment variable").entrySet()) {
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
                literal(process.env(), "environment variable"),
                null,
                null);
    }

    /**
     * The values of an env or header map exactly as registered.
     *
     * <p>A value may carry a {@code ${NAME}} placeholder — the syntax stays
     * storable, so registrations need no rewrite later — but nothing resolves it
     * yet. A placeholder will resolve from the calling user's own variables and
     * from nowhere else; until users have variables, it is refused here, before
     * anything starts.
     *
     * <p>Never from the environment of this process. {@code /mcp-gateway} asks
     * for a login, not an admin role, so a header reading
     * {@code ${MC_POSTGRES_PASSWORD}} would hand any signed-in user the database
     * password at a URL of their choosing.
     *
     * <p>Values only, never keys, and only these two maps: the image, the
     * command and the URL are literal anyway. A registration is data, not a
     * template language — the same line {@link #expandHome} draws.
     *
     * @throws McpGatewayException naming the field and the variable — never the
     *         value, which may carry a secret around the placeholder
     */
    private static Map<String, String> literal(Map<String, String> values, String what) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            Matcher placeholder = PLACEHOLDER.matcher(e.getValue() == null ? "" : e.getValue());
            if (placeholder.find()) {
                throw new McpGatewayException(what + " '" + e.getKey() + "' uses the variable ${"
                        + placeholder.group(1) + "}, but variables resolve only from a user's own "
                        + "variables, and there are none yet — enter the value itself");
            }
        }
        return values;
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
