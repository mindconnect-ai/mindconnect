package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.Map;

/**
 * Contributes variables to the {@link PromptRenderer} context.
 * <p>
 * Implementations are picked up automatically by the Spring application context
 * (annotate with {@code @Component}). New variables = new provider class — no
 * change to existing code is required.
 * <p>
 * Providers are sorted by {@link #priority()} ascending before being invoked, so
 * a higher-priority provider may overwrite values produced by a lower-priority
 * one. Default priority is 0.
 * <p>
 * Conventions for the {@code ctx} map:
 * <ul>
 *   <li>Use {@code snake_case} keys ({@code current_date}, {@code user_notes}).</li>
 *   <li>Prefer simple types: strings, numbers, lists/maps. Avoid live entities
 *       that hold open resources.</li>
 *   <li>Absent / not-applicable values: put {@code null} so {@code {% if x %}}
 *       blocks behave intuitively.</li>
 * </ul>
 */
public interface PromptContextProvider {

    /**
     * Lower runs first; later providers can overwrite earlier ones.
     * Cheap, deterministic providers (date, agent metadata) should keep the
     * default priority. Expensive ones (DB / file-system reads) may use a
     * higher priority so they only run when the prompt actually references them.
     */
    default int priority() {
        return 0;
    }

    /** Adds variables to the context map. */
    void contribute(Map<String, Object> ctx,
                    AgentDefinition def,
                    AgentSession session,
                    AuthenticationInfo auth);
}
