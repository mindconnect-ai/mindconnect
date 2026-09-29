package ai.mindconnect.agent.tools.code;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Thin wrapper around a docker-CLI-compatible container binary. Podman is
 * deliberately CLI-compatible with docker for everything this module uses
 * ({@code run}, {@code exec}, {@code rm}, {@code ps}, flags, labels), so the
 * backend is just "whichever binary answered {@code --version}" — docker,
 * podman, nerdctl, or an explicit path from configuration. Talking to the CLI
 * instead of the Docker Engine API is what keeps the backend swappable.
 */
public final class ContainerCli {

    /** Outcome of one CLI invocation. {@code exitCode} is -1 when timed out. */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() { return !timedOut && exitCode == 0; }
    }

    /** Captured output is capped; container logs can be arbitrarily large. */
    private static final int MAX_CAPTURED_CHARS = 64_000;

    private final String binary;

    private ContainerCli(String binary) {
        this.binary = binary;
    }

    public String binary() {
        return binary;
    }

    /**
     * Finds a working container binary. {@code auto} (or blank) probes podman
     * first — it is daemonless, rootless and licence-frictionless — then
     * docker. Any other value is taken as the binary name or path to probe.
     * Empty when nothing answers, which makes the tool unavailable rather
     * than broken.
     */
    public static Optional<ContainerCli> detect(String configured) {
        List<String> candidates = configured == null || configured.isBlank() || "auto".equals(configured)
                ? List.of("podman", "docker")
                : List.of(configured);
        for (String candidate : candidates) {
            try {
                ContainerCli cli = new ContainerCli(candidate);
                if (cli.run(Duration.ofSeconds(5), null, "--version").ok()) {
                    return Optional.of(cli);
                }
            } catch (UncheckedIOException e) {
                // Binary not on the PATH — try the next candidate.
            }
        }
        return Optional.empty();
    }

    /**
     * How long a process may run, and when that time is not its own.
     *
     * <p>A program that asked the host for a tool result is not running, it
     * is waiting — and the wait is the host's doing, not the program's. While
     * {@code waiting} says so, {@code budget} stops being spent. {@code
     * ceiling} is the wall clock that holds regardless, so a host that never
     * answers cannot keep a container alive forever.
     */
    public record Budget(Duration budget, Duration ceiling, BooleanSupplier waiting) {

        /** The plain case: the time is the time. */
        public static Budget of(Duration budget) {
            return new Budget(budget, budget, () -> false);
        }

        /** Runs for {@code budget} of its own time, at most {@code ceiling} in total. */
        public static Budget pausedWhile(Duration budget, Duration ceiling, BooleanSupplier waiting) {
            return new Budget(budget, ceiling == null || ceiling.compareTo(budget) < 0 ? budget : ceiling,
                    waiting == null ? () -> false : waiting);
        }
    }

    /** How often the wait is re-examined while a budget can be paused. */
    private static final long SLICE_MS = 100;

    /**
     * Runs {@code <binary> args...}, optionally feeding {@code stdin}, and
     * captures both output streams (capped). On timeout the process is killed
     * and the result is marked {@code timedOut} — the caller decides what
     * that means for the container.
     */
    public Result run(Duration timeout, String stdin, String... args) {
        return run(Budget.of(timeout), stdin, args);
    }

    /** Same, under a budget that may stop while the process waits on the host. */
    public Result run(Budget budget, String stdin, String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(binary);
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outReader = Thread.startVirtualThread(() -> capture(process.getInputStream(), out));
            Thread errReader = Thread.startVirtualThread(() -> capture(process.getErrorStream(), err));
            writeStdin(process, stdin);
            boolean finished = await(process, budget);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outReader.join(Duration.ofSeconds(2).toMillis());
            errReader.join(Duration.ofSeconds(2).toMillis());
            return new Result(finished ? process.exitValue() : -1, out.toString(), err.toString(), !finished);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + binary, e);
        }
    }

    /**
     * Waits for the process. Without a pause this is one {@code waitFor}; with
     * one it is the same wait in slices, spending the budget only while the
     * process is the one doing the work, and stopping at the ceiling either
     * way.
     */
    private static boolean await(Process process, Budget budget) throws InterruptedException {
        if (budget.budget().equals(budget.ceiling())) {
            return process.waitFor(budget.budget().toMillis(), TimeUnit.MILLISECONDS);
        }
        long remaining = budget.budget().toMillis();
        long hardDeadline = System.currentTimeMillis() + budget.ceiling().toMillis();
        while (remaining > 0 && System.currentTimeMillis() < hardDeadline) {
            long slice = Math.min(SLICE_MS, remaining);
            if (process.waitFor(slice, TimeUnit.MILLISECONDS)) {
                return true;
            }
            // Time spent waiting for the host is not the program's to lose.
            if (!budget.waiting().getAsBoolean()) {
                remaining -= slice;
            }
        }
        return !process.isAlive();
    }

    private static void writeStdin(Process process, String stdin) {
        // A dead process yields a broken pipe here; the exit code and stderr
        // already tell that story, so the write failure itself is noise.
        try (Writer writer = process.outputWriter(StandardCharsets.UTF_8)) {
            if (stdin != null) {
                writer.write(stdin);
            }
        } catch (IOException ignored) {
        }
    }

    private static void capture(InputStream in, StringBuilder sink) {
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                int room = MAX_CAPTURED_CHARS - sink.length();
                if (room > 0) {
                    sink.append(buf, 0, Math.min(n, room));
                }
                // Keep draining past the cap so the process never blocks on a
                // full pipe; the extra output is simply dropped.
            }
        } catch (IOException ignored) {
        }
    }
}
