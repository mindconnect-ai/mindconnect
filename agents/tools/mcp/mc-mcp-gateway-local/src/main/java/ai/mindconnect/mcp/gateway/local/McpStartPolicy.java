package ai.mindconnect.mcp.gateway.local;

import org.springframework.core.env.Environment;

/**
 * Which kinds of MCP server this installation starts on its own machine.
 *
 * <p>A {@code process} target runs a command with the rights of this server;
 * a {@code docker} target runs a container through its container runtime,
 * which — with a flag like {@code --privileged} or a mount of {@code /} — is
 * the same thing. Registering asks for a login, not an admin role (concept 21
 * §9.2 wants one; there is no role model yet), so whoever can sign in could run
 * anything. Whether these targets start is therefore a decision of the
 * installation, not of a registration (concept 21 §9.1). An {@code http}
 * target starts nothing here and needs no permission.
 *
 * <p>Unset, both follow sign-in. Without it the installation has one user —
 * the one at the keyboard, who can run the command in a terminal anyway — and
 * refusing them protects nobody. With sign-in on, both stay off until an
 * operator allows them.
 *
 * @param allowProcess whether {@code process} targets start
 * @param allowDocker  whether {@code docker} targets start
 */
public record McpStartPolicy(boolean allowProcess, boolean allowDocker) {

    static final String ALLOW_PROCESS = "mindconnect.mcp.allow-process";
    static final String ALLOW_DOCKER = "mindconnect.mcp.allow-docker";
    static final String AUTH_ENABLED = "mindconnect.auth.enabled";

    /** Both allowed — a single-user installation, or a test. */
    public static McpStartPolicy allowAll() {
        return new McpStartPolicy(true, true);
    }

    /** The two flags, each defaulting to "on exactly while nobody has to sign in". */
    public static McpStartPolicy from(Environment environment) {
        boolean signInOff = !environment.getProperty(AUTH_ENABLED, Boolean.class, false);
        return new McpStartPolicy(
                environment.getProperty(ALLOW_PROCESS, Boolean.class, signInOff),
                environment.getProperty(ALLOW_DOCKER, Boolean.class, signInOff));
    }
}
