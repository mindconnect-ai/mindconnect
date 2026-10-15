package ai.mindconnect.agent.tools.code;

import java.nio.file.Path;

/**
 * A directory of the host machine made visible inside the code-execution
 * container, at {@link #MOUNT_POINT}.
 *
 * <p>This is a deliberate hole in the container's isolation, so it is not
 * something the model decides. The directory comes from the operator — the
 * runtime's {@code codeExecMountDir} setting, or a {@code mountDir} override on
 * one agent's tool binding — never from a tool argument. A path chosen by
 * model output is a path that will eventually be {@code /} or {@code ~/.ssh}.
 *
 * <p>Read-only unless someone opts out. The container exists so that
 * model-written code runs where it cannot do damage; handing it write access
 * to real files gives that up, and should be a sentence someone typed on
 * purpose.
 */
public record HostMount(Path dir, boolean readOnly) {

    /** Where the host directory appears inside the container. */
    public static final String MOUNT_POINT = "/mnt/host";

    public HostMount {
        if (dir == null) {
            throw new IllegalArgumentException("HostMount.dir must not be null");
        }
    }

    /**
     * Part of the container's session key: the mount is fixed when the
     * container starts, so two bindings that disagree about it must not share
     * one. {@code null} — no mount — has its own value rather than an empty
     * string, so "no mount" and "a mount whose path stringifies to nothing"
     * can never collide.
     */
    public static String key(HostMount mount) {
        return mount == null ? "-" : mount.dir().toAbsolutePath() + (mount.readOnly() ? ":ro" : ":rw");
    }

    /** For the log line and the tool description. */
    public String describe() {
        return dir + " → " + MOUNT_POINT + (readOnly ? " (read-only)" : " (writable)");
    }
}
