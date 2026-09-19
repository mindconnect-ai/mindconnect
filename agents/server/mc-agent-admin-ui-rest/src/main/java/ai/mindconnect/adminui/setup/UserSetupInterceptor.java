package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.security.SecurityCurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * Runs {@link UserSetup} once per sign-in.
 *
 * <p>An interceptor rather than a filter, and deliberately: a check asks what
 * the namespace has and what the process has, and the scope that answers is
 * bound by a filter. By the time a request reaches the dispatcher every filter
 * has run, so this is the first place where the question has a full answer.
 *
 * <p>Once per session, remembered on the session — the same way namespace
 * onboarding does it, because this application has no login event either. A
 * request without a session (an API token, a bearer JWT) runs the checks and
 * remembers nothing: there is nowhere to remember it, and a program calling
 * the API is not reading notifications anyway.
 */
public class UserSetupInterceptor implements HandlerInterceptor {

    /** Set on the session once the checks have run, so they run once per sign-in. */
    static final String DONE = "mindconnect.setup.checked";

    private final UserSetup setup;

    public UserSetupInterceptor(UserSetup setup) {
        this.setup = Objects.requireNonNull(setup, "setup");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(DONE) != null) {
            return true;                      // no session to remember it on, or already done
        }
        UserId user = SecurityCurrentUserResolver
                .userIdOf(SecurityContextHolder.getContext().getAuthentication()).orElse(null);
        if (user == null) {
            return true;
        }
        // Marked before the pass, not after: a check that throws must not make
        // every request of this session try again in front of the user.
        session.setAttribute(DONE, Boolean.TRUE);
        setup.run(user);
        return true;
    }
}
