package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.common.AuthenticationInfo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Exposes the current date/time to system-prompt templates.
 * <p>
 * Variables provided:
 * <ul>
 *   <li>{@code current_date}    — ISO-8601 date e.g. {@code "2026-04-28"}</li>
 *   <li>{@code current_datetime}— ISO-8601 instant e.g. {@code "2026-04-28T18:42:00Z"}</li>
 *   <li>{@code current_time}    — local time e.g. {@code "18:42"}</li>
 * </ul>
 * The clock can be injected for tests.
 */
public class CurrentDateProvider implements PromptContextProvider {

    private final Clock clock;

    public CurrentDateProvider() {
        this(Clock.systemDefaultZone());
    }

    public CurrentDateProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void contribute(Map<String, Object> ctx,
                           AgentDefinition def,
                           AgentSession session,
                           AuthenticationInfo auth) {
        Instant now = Instant.now(clock);
        LocalDateTime local = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        ctx.put("current_date", LocalDate.from(local).toString());
        ctx.put("current_datetime", now.toString());
        ctx.put("current_time", local.format(DateTimeFormatter.ofPattern("HH:mm")));
    }
}
