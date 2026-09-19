package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Where the binding decorator looks up whose account a call runs on. The port;
 * the store behind it is {@code mc-credentials}, and a host that keeps no
 * connections binds none of this.
 */
public interface Connections {

    /** What {@code userId} attached for {@code provider} — their default first. */
    List<ToolConnection> of(UserId userId, String provider);

    /**
     * The connection a call should use: the one named by {@code key}, or the
     * user's default when {@code key} is null or blank. Empty when they have
     * none — which is a sentence for the model, not an exception.
     */
    Optional<ToolConnection> resolve(UserId userId, String provider, String key);

    /** No connections at all — the default for a host that assembled no store. */
    static Connections none() {
        return new Connections() {
            @Override public List<ToolConnection> of(UserId userId, String provider) { return List.of(); }
            @Override public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return Optional.empty();
            }
        };
    }
}
