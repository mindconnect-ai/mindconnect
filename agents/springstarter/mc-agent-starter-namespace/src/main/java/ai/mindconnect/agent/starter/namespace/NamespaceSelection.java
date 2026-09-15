package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Optional;

/**
 * The namespace a browser user chose to work in, kept in the HTTP session:
 * chosen once from the header switcher, honoured by every page and every
 * call the UI makes until the user picks another or the session ends. API
 * clients do not have it — they name the namespace on the request
 * (see {@link NamespacePathFilter} and {@link ScopeBindingFilter}).
 */
public final class NamespaceSelection {

    /** The session attribute. */
    public static final String SESSION_KEY = "mindconnect.namespace";

    private NamespaceSelection() {
    }

    /** Remembers {@code namespace} for the rest of the session (creating one if needed). */
    public static void select(HttpServletRequest request, Namespace namespace) {
        request.getSession(true).setAttribute(SESSION_KEY, namespace.value());
    }

    /** The selection, if the request has a session that holds one; never creates a session. */
    public static Optional<Namespace> selected(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof String s && !s.isBlank() ? Optional.of(new Namespace(s)) : Optional.empty();
    }

    /** Remembers {@code namespace} in the session the request already has; never creates one. */
    public static void rememberIfSession(HttpServletRequest request, Namespace namespace) {
        HttpSession session = request.getSession(false);
        if (session != null) session.setAttribute(SESSION_KEY, namespace.value());
    }

    /** Forgets the selection — the user is no longer a member, say. */
    public static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.removeAttribute(SESSION_KEY);
    }
}
