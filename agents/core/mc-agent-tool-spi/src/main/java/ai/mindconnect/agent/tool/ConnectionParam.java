package ai.mindconnect.agent.tool;

import java.util.Objects;

/**
 * One end of a tool that has to be pointed at an account.
 *
 * <p>Most tools have exactly one — {@code ("account", "email", true)} — and
 * for those the framework hides it wherever it can: a user with a single
 * connection never sees a parameter at all. A tool that moves something
 * between two accounts declares two, {@code ("from", "calendar")} and
 * {@code ("to", "calendar")}, and the model fills both.
 *
 * @param name     the parameter as it appears in the tool's schema
 * @param provider which kind of connection fits here; both ends of a copy
 *                 must name the same one, or nothing could be copied between them
 * @param required whether the tool can run without it
 */
public record ConnectionParam(String name, String provider, boolean required) {

    public ConnectionParam {
        Objects.requireNonNull(name, "A connection parameter needs a name");
        Objects.requireNonNull(provider, "A connection parameter needs a provider");
    }

    /** The usual case: one account, and the tool cannot work without it. */
    public static ConnectionParam account(String provider) {
        return new ConnectionParam("account", provider, true);
    }

    public static ConnectionParam of(String name, String provider) {
        return new ConnectionParam(name, provider, true);
    }
}
