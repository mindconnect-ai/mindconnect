package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who may reach a vector store. A knowledge base — a {@code GLOBAL} or
 * {@code AGENT} store — is shared configuration, open to every authenticated
 * caller. A chat's upload store holds what one user attached to one chat, so
 * it answers only to the user the chat belongs to; for anyone else it does
 * not exist.
 *
 * <p>Two things make a store a chat's upload store: a registration with scope
 * {@code SESSION}, whose {@code scopeRef} is the session id, and the
 * {@value #CHAT_STORE_PREFIX} name the upload pipeline gives it. The name
 * counts for a store that is not registered yet, or somebody could create
 * {@code session-<someone else's session>} and read what lands there later —
 * which is also why nobody may create a store under such a name by hand.
 */
@Component
public class VectorStoreAccess {

    /** The name prefix of a chat's upload store: {@code session-<sessionId>}. */
    public static final String CHAT_STORE_PREFIX = "session-";

    /** Why a store cannot be created under a chat upload store's name. */
    public static final String RESERVED_NAME =
            "Store names starting with '" + CHAT_STORE_PREFIX + "' belong to chat upload stores — choose another name.";

    private final SessionAccess sessions;

    public VectorStoreAccess(SessionAccess sessions) {
        this.sessions = sessions;
    }

    /** Whether {@code caller} may reach the registered store {@code instance}. */
    public boolean reachable(VectorStoreInstance instance, UserId caller) {
        return reachable(instance.name(), instance, caller);
    }

    /**
     * Whether {@code caller} may reach the store {@code name}.
     *
     * @param registered the store's registration; null when it has none
     */
    public boolean reachable(String name, VectorStoreInstance registered, UserId caller) {
        Optional<String> chat = chatSessionOf(name, registered);
        return chat.isEmpty() || ownsSession(chat.get(), caller);
    }

    /** Whether {@code name} is reserved for chat upload stores. */
    public static boolean isChatStoreName(String name) {
        return name != null && name.strip().startsWith(CHAT_STORE_PREFIX);
    }

    /** The session a chat upload store belongs to; empty for a knowledge base. */
    static Optional<String> chatSessionOf(String name, VectorStoreInstance registered) {
        if (registered != null && registered.scope() == VectorStoreInstance.Scope.SESSION) {
            return Optional.of(registered.scopeRef() == null ? "" : registered.scopeRef());
        }
        if (isChatStoreName(name)) {
            return Optional.of(name.strip().substring(CHAT_STORE_PREFIX.length()));
        }
        return Optional.empty();
    }

    private boolean ownsSession(String sessionId, UserId caller) {
        try {
            return sessions.owned(SessionId.of(sessionId), caller).isPresent();
        } catch (IllegalArgumentException notASessionId) {
            return false;
        }
    }
}
