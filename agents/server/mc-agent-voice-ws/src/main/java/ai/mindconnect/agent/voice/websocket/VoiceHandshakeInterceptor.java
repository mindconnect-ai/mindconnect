package ai.mindconnect.agent.voice.websocket;

import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * Takes the caller and the namespace scope from the handshake request — the
 * only moment the security context and the request's scope binding exist —
 * and keeps them with the connection. A handshake without an authenticated
 * caller is refused with 401.
 */
public final class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER = "mindconnect.voice.user";
    static final String SCOPE = "mindconnect.voice.scope";

    private final CurrentUsers currentUsers;
    private final ScopeSupplier scope;

    public VoiceHandshakeInterceptor(CurrentUsers currentUsers, ScopeSupplier scope) {
        this.currentUsers = currentUsers;
        this.scope = scope;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        Optional<UserId> user = currentUsers.current();
        if (user.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER, user.get());
        currentScope().ifPresent(bound -> attributes.put(SCOPE, bound));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }

    private Optional<Scope> currentScope() {
        try {
            return Optional.of(scope.get());
        } catch (IllegalStateException unbound) {
            // A host without per-request scopes: the runtime's fallback applies on every thread anyway.
            return Optional.empty();
        }
    }
}
