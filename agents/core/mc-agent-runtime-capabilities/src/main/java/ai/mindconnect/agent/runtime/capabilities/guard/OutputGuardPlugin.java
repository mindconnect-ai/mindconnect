package ai.mindconnect.agent.runtime.capabilities.guard;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Output guards: runs the {@link OutputGuard}s that hold for the session over a turn's final answer. */
public final class OutputGuardPlugin implements Plugin {

    @Override
    public String name() {
        return "output-guards";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.after(RuntimeHook.ANSWER, (context, answer) -> {
            TurnMessage current = answer;
            List<OutputGuard> guards = registry.all(GuardResolver.class).stream()
                    .flatMap(resolver -> resolver.outputGuards(context.session()).stream())
                    .toList();
            for (OutputGuard guard : guards) {
                current = guard.check(context, current);
            }
            return current;
        });
    }
}
