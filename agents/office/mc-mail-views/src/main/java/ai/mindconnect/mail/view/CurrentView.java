package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which view a person has on the screen right now — so an agent asked
 * "what am I looking at" can answer, and a screen can open where it was left.
 *
 * <p>A port, because where this is kept is the host's business: a preference
 * store that survives a restart where there is one, memory where there is
 * not. {@link Memory} is the one that ships.
 */
public interface CurrentView {

    Optional<ViewId> current(UserId user);

    void current(UserId user, ViewId id);

    /** In memory, per user — gone with the server, which for a "where was I" is bearable. */
    final class Memory implements CurrentView {
        private final Map<UserId, ViewId> current = new ConcurrentHashMap<>();

        @Override public Optional<ViewId> current(UserId user) {
            return Optional.ofNullable(current.get(user));
        }

        @Override public void current(UserId user, ViewId id) {
            if (id == null) current.remove(user); else current.put(user, id);
        }
    }
}
