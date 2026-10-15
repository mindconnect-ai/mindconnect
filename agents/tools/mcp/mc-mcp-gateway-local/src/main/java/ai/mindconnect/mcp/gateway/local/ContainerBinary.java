package ai.mindconnect.mcp.gateway.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds a container CLI to run stdio MCP images with. {@code auto} (or
 * blank) probes podman first — daemonless and rootless — then docker; any
 * other value is taken as a binary name or path.
 *
 * <p>Same rule and the same reasoning as {@code ContainerCli.detect} in
 * {@code mc-agent-tools-code}, minus everything that module needs on top
 * (exec, logs, capture). The duplication is deliberate: a gateway must not
 * depend on a tool module. If a third caller turns up, the detection is
 * worth its own home in common — until then two dozen lines are cheaper
 * than the wrong dependency.
 */
public final class ContainerBinary {

    private static final Logger log = LoggerFactory.getLogger(ContainerBinary.class);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private ContainerBinary() {
    }

    /**
     * @param configured a binary name or path, or {@code auto}/blank to probe
     * @return the first binary that answered {@code --version}, empty when
     *         none did — which makes container-based servers unavailable
     *         rather than broken
     */
    public static Optional<String> detect(String configured) {
        List<String> candidates = configured == null || configured.isBlank() || "auto".equals(configured)
                ? List.of("podman", "docker")
                : List.of(configured);
        for (String candidate : candidates) {
            if (answersVersion(candidate)) {
                return Optional.of(candidate);
            }
        }
        log.info("no container runtime found (configured={}) — container-based MCP servers stay unavailable",
                configured);
        return Optional.empty();
    }

    private static boolean answersVersion(String binary) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (java.io.IOException e) {
            return false;   // not on the PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
