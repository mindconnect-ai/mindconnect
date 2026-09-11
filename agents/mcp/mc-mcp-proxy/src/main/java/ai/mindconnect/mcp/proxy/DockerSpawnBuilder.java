package ai.mindconnect.mcp.proxy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link McpStdioSpawn} that runs a container in {@code -i --rm}
 * mode and pipes JSON-RPC over stdio.
 *
 * <p>Covers the minimal case an stdio MCP image needs: volumes, env vars,
 * optional command override. Compose-style or network configuration comes
 * later.
 *
 * <p>The binary defaults to {@code docker} and can be any docker-compatible
 * CLI — podman and nerdctl take the same {@code run -i --rm -v -e} flags.
 * Callers that want auto-detection resolve the binary themselves and pass
 * it to {@link #of(String, String)}.
 *
 * <p>Example:
 * <pre>{@code
 *   McpStdioSpawn spawn = DockerSpawnBuilder.of("ghcr.io/gongrzhe/gmail-mcp:latest")
 *       .mount(Path.of(System.getProperty("user.home"), ".gmail-mcp"), "/root/.gmail-mcp")
 *       .build();
 * }</pre>
 */
public final class DockerSpawnBuilder {

    /** Container CLI used when the caller names none. */
    public static final String DEFAULT_BINARY = "docker";

    private final String binary;
    private final String image;
    private final List<String> extraDockerFlags = new ArrayList<>();
    private final List<String[]> volumes = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private List<String> commandOverride;
    private Duration startupTimeout = Duration.ofSeconds(45);
    private Duration callTimeout = Duration.ofSeconds(60);

    private DockerSpawnBuilder(String binary, String image) {
        if (binary == null || binary.isBlank()) {
            throw new IllegalArgumentException("binary required");
        }
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image required");
        }
        this.binary = binary;
        this.image = image;
    }

    /** Runs the image with {@value #DEFAULT_BINARY}. */
    public static DockerSpawnBuilder of(String image) {
        return new DockerSpawnBuilder(DEFAULT_BINARY, image);
    }

    /** Runs the image with the given docker-compatible binary, e.g. {@code podman}. */
    public static DockerSpawnBuilder of(String binary, String image) {
        return new DockerSpawnBuilder(binary, image);
    }

    /** Bind-mount {@code hostPath} into the container at {@code containerPath}. */
    public DockerSpawnBuilder mount(String hostPath, String containerPath) {
        if (hostPath == null || containerPath == null) {
            throw new IllegalArgumentException("hostPath/containerPath required");
        }
        volumes.add(new String[]{ hostPath, containerPath });
        return this;
    }

    /** Set/replace an env-var that will be passed via {@code -e KEY=VALUE}. */
    public DockerSpawnBuilder env(String key, String value) {
        env.put(key, value);
        return this;
    }

    /** Add a raw flag to {@code docker run} (e.g. {@code "--network=host"}). */
    public DockerSpawnBuilder dockerFlag(String... flag) {
        for (String f : flag) extraDockerFlags.add(f);
        return this;
    }

    /** Override the container ENTRYPOINT/CMD (everything after the image name). */
    public DockerSpawnBuilder commandOverride(List<String> command) {
        this.commandOverride = command == null ? null : List.copyOf(command);
        return this;
    }

    public DockerSpawnBuilder startupTimeout(Duration d) { this.startupTimeout = d; return this; }
    public DockerSpawnBuilder callTimeout(Duration d)    { this.callTimeout = d; return this; }

    public McpStdioSpawn build() {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-i");          // stdin attached — mandatory for MCP stdio
        args.add("--rm");        // cleanup on exit

        for (String[] vol : volumes) {
            args.add("-v");
            args.add(vol[0] + ":" + vol[1]);
        }
        // Env vars additionally travel to Docker via -e so the MCP process
        // sees them INSIDE the container. They are deliberately NOT passed as
        // McpStdioSpawn.env() — that would be the environment of the `docker`
        // command itself, not of the container.
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("-e");
            args.add(e.getKey() + "=" + e.getValue());
        }
        args.addAll(extraDockerFlags);
        args.add(image);
        if (commandOverride != null) {
            args.addAll(commandOverride);
        }

        return new McpStdioSpawn(
                binary,
                args,
                Map.of(),           // siehe Kommentar oben
                startupTimeout,
                callTimeout
        );
    }
}
