package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.UserId;

/**
 * Says who is acting right now — on a server the user who is signed in,
 * which need not be the user a session belongs to. Like the namespace it
 * belongs to the running code, so the runtime carries it along: it notes
 * {@link #current()} where a turn is submitted and runs the turn's work in
 * {@link #callAs}, sub-agent turns included.
 *
 * <p>The runtime itself never asks who acts. It carries the answer for the
 * stores and tools behind it that do: a user's own variables, the accounts
 * a user attached.
 */
public interface UserProvider {

    /** Who acts on the thread that asks; empty where nobody is known. */
    Optional<UserId> current();

    /** Runs the body with {@code user} as the one who acts, and restores who it was before. */
    <T> T callAs(UserId user, Supplier<T> body);

    /** Nobody acts and nobody needs to: a library in a program of its own, a command line. */
    static UserProvider none() {
        return new UserProvider() {
            @Override
            public Optional<UserId> current() {
                return Optional.empty();
            }

            @Override
            public <T> T callAs(UserId ignored, Supplier<T> body) {
                return body.get();
            }
        };
    }
}
