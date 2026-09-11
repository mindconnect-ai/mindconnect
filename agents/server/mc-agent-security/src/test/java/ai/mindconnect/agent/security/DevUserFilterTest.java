package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** With authentication off, every request is the dev user — as a login, so the rest of the app sees nothing special. */
class DevUserFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aRequestRunsAsTheDevUser() throws Exception {
        AtomicReference<Optional<UserId>> seen = new AtomicReference<>();

        new DevUserFilter("dev").doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> seen.set(new SecurityCurrentUserResolver().currentUser()));

        assertThat(seen.get()).contains(UserId.of("dev"));
    }
}
