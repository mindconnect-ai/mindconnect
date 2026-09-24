package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt states the time in the user's zone, with the zone and its
 * offset, and says what a time without an offset means — whatever zone the
 * server runs in.
 */
class CurrentTimeSectionTest {

    /** Thursday, 24 September 2026, 15:36 UTC — summer time in Zurich and New York. */
    private static final Instant SUMMER = Instant.parse("2026-09-24T15:36:00Z");
    /** Tuesday, 15 December 2026, 15:36 UTC — winter time in both. */
    private static final Instant WINTER = Instant.parse("2026-12-15T15:36:00Z");

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private static final TimeZones ZONES = user -> user == null ? ZoneOffset.UTC
            : user.equals(ALICE) ? ZoneId.of("Europe/Zurich") : ZoneId.of("America/New_York");

    private static AgentSession sessionOf(UserId user) {
        return AgentSession.start(AgentId.random(), user, ConversationId.random());
    }

    private static String render(Instant now, UserId user) {
        return new CurrentTimeSection(Clock.fixed(now, ZoneOffset.UTC), ZONES).render(null, sessionOf(user));
    }

    @Test
    void zurichInSummer() {
        assertThat(render(SUMMER, ALICE))
                .startsWith("\n\n## Date and time\n")
                .contains("Today is Thursday, 24 September 2026 (Europe/Zurich, UTC+02:00).")
                .contains("A time without an offset is a time in Europe/Zurich")
                .contains("2026-09-25T16:16 is 16:16 in Europe/Zurich")
                .contains("call get_current_datetime")
                // To the day: a minute in the system prompt would miss the provider's prompt cache.
                .doesNotContain("17:36");
    }

    @Test
    void zurichInWinter() {
        assertThat(render(WINTER, ALICE))
                .contains("Today is Tuesday, 15 December 2026 (Europe/Zurich, UTC+01:00).");
    }

    @Test
    void newYork() {
        assertThat(render(SUMMER, BOB)).contains("Today is Thursday, 24 September 2026 (America/New_York, UTC-04:00).")
                .contains("A time without an offset is a time in America/New_York");
        assertThat(render(WINTER, BOB)).contains("(America/New_York, UTC-05:00)");
    }

    @Test
    void aSessionOnNobodysBehalfGetsTheInstallationsZone() {
        assertThat(new CurrentTimeSection(Clock.fixed(SUMMER, ZoneOffset.UTC), ZONES).render(null, null))
                .contains("Today is Thursday, 24 September 2026 (Z, UTC+00:00).");
        assertThat(CurrentTimeSection.line(SUMMER, ZoneId.of("UTC")))
                .isEqualTo("Today is Thursday, 24 September 2026 (UTC, UTC+00:00).");
    }

    @Test
    void theTemplateVariablesAreInTheUsersZoneToo() {
        CurrentDateProvider provider = new CurrentDateProvider(Clock.fixed(Instant.parse("2026-09-24T22:30:00Z"),
                ZoneOffset.UTC), ZONES);
        Map<String, Object> ctx = new HashMap<>();

        provider.contribute(ctx, null, sessionOf(BOB), AuthenticationInfo.of(ALICE));

        // The caller wins over the session's owner; in Zurich it is already the 25th.
        assertThat(ctx).containsEntry("current_date", "2026-09-25").containsEntry("current_time", "00:30")
                .containsEntry("time_zone", "Europe/Zurich")
                .containsEntry("current_datetime", "2026-09-24T22:30:00Z");

        ctx.clear();
        provider.contribute(ctx, null, sessionOf(BOB), null);
        assertThat(ctx).containsEntry("current_date", "2026-09-24").containsEntry("current_time", "18:30")
                .containsEntry("time_zone", "America/New_York");
    }

    @Test
    void theSectionIsPartOfTheSystemPrompt() {
        PromptRenderer template = new PromptRenderer() {
            @Override public String render(String t, AgentDefinition def, AgentSession session,
                                           AuthenticationInfo auth, Map<String, Object> extra) {
                return t;
            }
        };
        MemoryStrategy noMemory = new MemoryStrategy() {
            @Override public String kind() { return "none"; }
            @Override public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                                          AuthenticationInfo auth) { return List.of(); }
            @Override public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(
                    AgentDefinition def, AgentSession session) { return List.of(); }
            @Override public String systemPromptAddendum(AgentDefinition def, AgentSession session) { return ""; }
            @Override public CompressResult compress(AgentDefinition def, AgentSession session,
                                                     AuthenticationInfo auth) { return CompressResult.empty(); }
            @Override public TokenCounter resolveTokenCounter(AgentDefinition def) { return null; }
            @Override public Integer contextWindowTokens(AgentDefinition def) { return null; }
        };
        AgentDefinition def = new AgentDefinition(AgentId.random(), "main", "d", null, null,
                "You help.", null, "gpt", 10, null,
                ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus.ACTIVE, List.of(), List.of(),
                List.of(), null, AgentDefinition.SkillsConfig.ALL, Instant.now(), Instant.now());
        AgentSession session = AgentSession.start(def.id(), ALICE, ConversationId.random());

        String prompt = SystemPromptRenderer.render(template, noMemory, def, session, AuthenticationInfo.of(ALICE),
                InstructionFiles.projectOnly(), SkillCatalog.none(),
                PromptSections.of(List.of(new CurrentTimeSection(Clock.fixed(SUMMER, ZoneOffset.UTC), ZONES))));

        assertThat(prompt).startsWith("You help.")
                .contains("Today is Thursday, 24 September 2026 (Europe/Zurich, UTC+02:00).");
    }
}
