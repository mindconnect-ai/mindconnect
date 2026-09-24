package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** A tool asks the environment for the user's zone, and gets the JVM's when the host has no resolver. */
class TimeZonesTest {

    @Test
    void aHostWithoutAResolverGetsTheJvmsZone() {
        TimeZones zones = TimeZones.of(MapToolEnvironment.builder().build());

        assertThat(zones.zoneOf(UserId.of("alice"))).isEqualTo(ZoneId.systemDefault());
        assertThat(TimeZones.of(null).zoneOf(null)).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void theHostsResolverIsTheOneTheToolsGet() {
        TimeZones perUser = user -> user != null && user.value().equals("alice")
                ? ZoneId.of("Europe/Zurich") : ZoneId.of("America/New_York");
        ToolEnvironment env = MapToolEnvironment.builder().service(TimeZones.class, perUser).build();

        assertThat(TimeZones.of(env).zoneOf(UserId.of("alice"))).isEqualTo(ZoneId.of("Europe/Zurich"));
        assertThat(TimeZones.of(env).zoneOf(UserId.of("bob"))).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(TimeZones.fixed(ZoneId.of("Asia/Tokyo")).zoneOf(UserId.of("anyone")))
                .isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void parseTakesZoneIdsAndNothingElse() {
        assertThat(TimeZones.parse("Europe/Zurich")).contains(ZoneId.of("Europe/Zurich"));
        assertThat(TimeZones.parse(" UTC ")).contains(ZoneId.of("UTC"));
        assertThat(TimeZones.parse("Mars/Olympus_Mons")).isEmpty();
        assertThat(TimeZones.parse("")).isEmpty();
        assertThat(TimeZones.parse(null)).isEmpty();
        assertThat(TimeZones.parse("../../etc/passwd")).isEmpty();
    }
}
