package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import ai.mindconnect.user.service.UserTimeZones;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser's zone, which the Admin UI's shell sends as a cookie, becomes
 * the user's the first time it is seen — once, never over their choice, and
 * without a read of the user store on every request after that.
 */
class BrowserTimeZoneTest {

    private static final UserId DEV = UserId.of("dev");

    /** Counts reads, to show that a settled user costs none. */
    private final AtomicInteger reads = new AtomicInteger();
    private final InMemoryUserRepository store = new InMemoryUserRepository() {
        @Override public Optional<User> findById(UserId id) {
            reads.incrementAndGet();
            return super.findById(id);
        }
    };
    private final UserService users = new UserService(store);
    private final UserRecorder recorder = new UserRecorder(users);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** One request of the dev user through the dev and the recording filter, with the shell's cookie when given. */
    private void request(String zone) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (zone != null) request.setCookies(new Cookie(UserRecorder.TIME_ZONE_COOKIE, zone));
        new DevUserFilter(DEV.value()).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> new UserRecordingFilter(recorder).doFilter(req, res, new MockFilterChain()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFirstZoneSeenIsStored_andALaterOneIsNot() throws Exception {
        request("Europe%2FZurich");
        assertThat(users.timeZone(DEV)).contains("Europe/Zurich");

        request("America/New_York");
        assertThat(users.timeZone(DEV)).as("stored once").contains("Europe/Zurich");
        assertThat(new UserTimeZones(users).zoneOf(DEV)).isEqualTo(ZoneId.of("Europe/Zurich"));
    }

    @Test
    void onceAUserHasAZone_theirRequestsReadNothingForIt() throws Exception {
        request("Europe/Zurich");
        int after = reads.get();

        for (int i = 0; i < 5; i++) request("Asia/Tokyo");

        assertThat(reads.get()).isEqualTo(after);
    }

    @Test
    void aChosenZoneIsNeverOverwrittenByTheBrowser() throws Exception {
        request(null);
        users.setTimeZone(DEV, "Asia/Kolkata");

        request("Europe/Zurich");

        assertThat(users.timeZone(DEV)).contains("Asia/Kolkata");
    }

    @Test
    void noCookie_orOneThatIsNoZone_leavesTheUserOnTheInstallationsDefault() throws Exception {
        request(null);
        request("Nowhere/Special");
        request("%ZZ");

        assertThat(users.find(DEV)).get().extracting(User::timeZone).isNull();
        assertThat(new UserTimeZones(users, ZoneId.of("UTC")).zoneOf(DEV)).isEqualTo(ZoneId.of("UTC"));
        assertThat(TimeZones.parse("Nowhere/Special")).isEmpty();
    }
}
