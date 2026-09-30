package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.service.prompt.PromptSections;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every runtime states the date and time in the system prompt, in the zone of
 * the session's user — from the builder, from the host's beans, or the JVM's.
 */
class TimeZonesInTheRuntimeTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private static AgentSession sessionOf(UserId user) {
        return AgentSession.start(AgentId.random(), user, ConversationId.random());
    }

    @Test
    void theBuildersResolverDecidesPerUser() throws Exception {
        TimeZones zones = user -> ALICE.equals(user) ? ZoneId.of("Europe/Zurich") : ZoneId.of("America/New_York");
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory()).timeZones(zones).build()) {
            PromptSections sections = runtime.beans().get(PromptSections.class);

            assertThat(sections.render(null, sessionOf(ALICE))).contains("## Date and time")
                    .contains("(Europe/Zurich, UTC+0");
            assertThat(sections.render(null, sessionOf(BOB))).contains("(America/New_York, UTC-0");
            assertThat(runtime.beans().get(TimeZones.class)).isSameAs(zones);
        }
    }

    @Test
    void theHostsResolverIsFoundOnFirstUse_andKept() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        TimeZones tokyo = TimeZones.fixed(ZoneId.of("Asia/Tokyo"));
        Map<Class<?>, Object> host = Map.of(TimeZones.class, tokyo);
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory())
                .beanFallback(type -> {
                    if (type == TimeZones.class) lookups.incrementAndGet();
                    return Optional.ofNullable(host.get(type));
                })
                .build()) {
            PromptSections sections = runtime.beans().get(PromptSections.class);

            assertThat(sections.render(null, sessionOf(ALICE))).contains("(Asia/Tokyo, UTC+09:00)");
            assertThat(sections.render(null, sessionOf(BOB))).contains("(Asia/Tokyo, UTC+09:00)");
            assertThat(lookups.get()).isEqualTo(1);
        }
    }

    @Test
    void withoutAResolverTheJvmsZone() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory()).build()) {
            assertThat(runtime.beans().get(PromptSections.class).render(null, sessionOf(ALICE)))
                    .contains("(" + ZoneId.systemDefault().getId() + ", UTC");
        }
    }
}
