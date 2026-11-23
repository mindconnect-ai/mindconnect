package ai.mindconnect.agent.runtime.capabilities.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;

class PromptPluginTest {

    private static final AgentDefinition AGENT = new AgentDefinition("planner", "Plans.", "You plan.",
            ModelSettings.NONE, List.of(ToolBinding.of("bash")), 5, Map.of());
    // 22:30 UTC on the 24th is half past midnight on the 25th in Zurich.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T22:30:00Z"), ZoneOffset.UTC);

    private final PluginRegistry registry = new DefaultPluginRegistry()
            .install(new PromptPlugin(CLOCK, user -> ZoneId.of("Europe/Zurich")));

    @Test
    void a_template_can_refer_to_the_agent_the_user_and_the_users_date() {
        Map<String, Object> variables = variables(session(UserId.of("ada")));

        assertThat(variables).containsEntry("agent_name", "planner").containsEntry("user_id", "ada")
                .containsEntry("session_id", "s1").containsEntry("current_date", "2026-09-25")
                .containsEntry("current_time", "00:30").containsEntry("time_zone", "Europe/Zurich")
                .containsEntry("current_datetime", "2026-09-24T22:30:00Z")
                .containsEntry("tools", List.of(Map.of("name", "bash", "description", "")));
    }

    @Test
    void a_session_nobody_owns_has_no_user_id() {
        assertThat(variables(session(null))).containsEntry("user_id", null);
    }

    @Test
    void the_instructions_say_what_day_it_is_for_the_user() {
        AgentContext context = new AgentContext(session(UserId.of("ada")), AGENT, TurnId.of("t1"));

        String instructions = registry.run(RuntimeHook.INSTRUCTIONS, context, ignored -> "You plan.");

        assertThat(instructions).startsWith("You plan.\n\n## Date and time\n"
                + "Today is Friday, 25 September 2026 (Europe/Zurich, UTC+02:00).");
    }

    private Map<String, Object> variables(Session session) {
        Map<String, Object> variables = new HashMap<>();
        registry.all(PromptVariableProvider.class).forEach(provider ->
                variables.putAll(provider.variables(AGENT, session)));
        return variables;
    }

    private static Session session(UserId user) {
        return new Session(SessionId.of("s1"), user, null, null, Map.of(), Instant.EPOCH);
    }
}
