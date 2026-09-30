package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a user's zone comes from: their choice, else what their browser
 * reported the first time — once — else the installation's default.
 */
class UserTimeZonesTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final MapRepositories.Users users = new MapRepositories.Users();
    private final UserService service = new UserService(users,
            new MapRepositories.SettableClock(Instant.parse("2026-09-24T15:36:00Z")));

    @Test
    void aUserWithoutAZone_getsTheInstallationsDefault_andNobodyGetsItToo() {
        service.recordLogin(ALICE, "sub", "iss", "Alice", null);
        UserTimeZones zones = new UserTimeZones(service, ZoneId.of("Asia/Tokyo"));

        assertThat(zones.zoneOf(ALICE)).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(zones.zoneOf(UserId.of("never-seen"))).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(zones.zoneOf(null)).isEqualTo(ZoneId.of("Asia/Tokyo"));
        // Without a configured default: the JVM's.
        assertThat(new UserTimeZones(service).zoneOf(ALICE)).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void eachUserGetsTheirOwnZone() {
        service.setTimeZone(ALICE, "Europe/Zurich");
        service.setTimeZone(BOB, "America/New_York");
        UserTimeZones zones = new UserTimeZones(service, ZoneId.of("UTC"));

        assertThat(zones.zoneOf(ALICE)).isEqualTo(ZURICH);
        assertThat(zones.zoneOf(BOB)).isEqualTo(NEW_YORK);
    }

    @Test
    void theZoneIsSavedValidatedAndNormalised_andBlankForgetsIt() {
        service.recordLogin(ALICE, "sub", "iss", "Alice", null);

        assertThat(service.setTimeZone(ALICE, " Europe/Zurich ").timeZone()).isEqualTo("Europe/Zurich");
        assertThat(service.timeZone(ALICE)).contains("Europe/Zurich");

        assertThatThrownBy(() -> service.setTimeZone(ALICE, "Mars/Olympus_Mons"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a time zone");
        assertThatThrownBy(() -> service.setTimeZone(ALICE, "Europe/Zurich; DROP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.timeZone(ALICE)).as("a refused value changes nothing").contains("Europe/Zurich");

        assertThat(service.setTimeZone(ALICE, "").timeZone()).isNull();
        assertThat(service.find(ALICE)).get().extracting(User::timeZone).isNull();
    }

    @Test
    void theZoneOfAnUnseenUserCreatesTheirRecord() {
        User carol = service.setTimeZone(UserId.of("carol"), "Asia/Kolkata");

        assertThat(carol.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(service.find(UserId.of("carol"))).contains(carol);
    }

    @Test
    void theBrowsersZoneIsStoredOnce_andNeverOverAChoice() {
        service.recordLogin(ALICE, "sub", "iss", "Alice", null);

        assertThat(service.adoptTimeZone(ALICE, "Europe/Zurich")).contains("Europe/Zurich");
        int saves = users.saves;
        // Another browser in another zone: the first one stays.
        assertThat(service.adoptTimeZone(ALICE, "America/New_York")).contains("Europe/Zurich");
        assertThat(users.saves).as("nothing written the second time").isEqualTo(saves);

        service.setTimeZone(ALICE, "Asia/Tokyo");
        assertThat(service.adoptTimeZone(ALICE, "Europe/Zurich")).contains("Asia/Tokyo");
        assertThat(service.timeZone(ALICE)).contains("Asia/Tokyo");
    }

    @Test
    void aBrowserZoneThatIsNoZone_orAUserWithoutARecord_storesNothing() {
        service.recordLogin(ALICE, "sub", "iss", "Alice", null);

        assertThat(service.adoptTimeZone(ALICE, "not/a zone")).isEmpty();
        assertThat(service.adoptTimeZone(UserId.of("ghost"), "Europe/Zurich")).isEmpty();
        assertThat(service.find(UserId.of("ghost"))).isEmpty();
        assertThat(service.timeZone(ALICE)).isEmpty();
    }

    @Test
    void aLoginKeepsTheZone() {
        service.recordLogin(ALICE, "sub", "iss", "Alice", null);
        service.setTimeZone(ALICE, "Europe/Zurich");

        User later = service.recordLogin(ALICE, "sub", "iss", "Alice Smith", "alice@example.com");

        assertThat(later.timeZone()).isEqualTo("Europe/Zurich");
        assertThat(later.withEnvironment(java.util.Map.of("A", "1")).withActiveNamespace("x").timeZone())
                .isEqualTo("Europe/Zurich");
    }

    @Test
    void aStoredZoneThisJvmDoesNotKnow_fallsBackRatherThanFailing() {
        users.save(new User(ALICE, null, null, null, null, null, null, null, null, "Atlantis/Poseidonia"));

        assertThat(new UserTimeZones(service, ZURICH).zoneOf(ALICE)).isEqualTo(ZURICH);
    }
}
