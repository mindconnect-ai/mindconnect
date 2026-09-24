package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.mail.MailAccounts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A message's time is shown in the zone of the user who asks — per call, not per server. */
class MailToolsZoneTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final MemoryMail mail = new MemoryMail().box("work");
    private final Map<UserId, ZoneId> zones = new HashMap<>(Map.of(
            ALICE, ZoneId.of("Europe/Zurich"), BOB, ZoneId.of("America/New_York")));

    private MailToolProvider provider() {
        MailToolProvider provider = new MailToolProvider();
        provider.bind(MapToolEnvironment.builder()
                .service(MailAccounts.class, mail.accounts())
                .service(TimeZones.class, user -> zones.get(user))
                .build());
        return provider;
    }

    private static String call(MailToolProvider provider, String tool, UserId user, Map<String, Object> args) {
        return provider.create(tool, null, ToolCallScope.detached(user)).orElseThrow().execute(args);
    }

    @Test
    void theSameMessageReadsInEachUsersOwnZone() {
        String id = mail.deliver("work", "INBOX", "Your ticket", "sbb@example.com",
                Instant.parse("2026-09-24T15:36:00Z"));
        MailToolProvider provider = provider();

        assertThat(call(provider, "mail_read", ALICE, Map.of("id", id))).contains("received: 2026-09-24 17:36");
        assertThat(call(provider, "mail_read", BOB, Map.of("id", id))).contains("received: 2026-09-24 11:36");
        assertThat(call(provider, "mail_list", ALICE, Map.of())).contains("received: 2026-09-24 17:36");
    }

    @Test
    void winterTimeAndAChangeOfZoneBetweenCalls() {
        String id = mail.deliver("work", "INBOX", "Invoice", "shop@example.com",
                Instant.parse("2026-12-15T15:36:00Z"));
        MailToolProvider provider = provider();

        assertThat(call(provider, "mail_read", ALICE, Map.of("id", id))).contains("received: 2026-12-15 16:36");
        zones.put(ALICE, ZoneId.of("Asia/Tokyo"));
        assertThat(call(provider, "mail_read", ALICE, Map.of("id", id))).contains("received: 2026-12-16 00:36");
    }
}
