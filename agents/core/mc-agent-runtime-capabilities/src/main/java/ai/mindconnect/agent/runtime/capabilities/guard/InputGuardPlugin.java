package ai.mindconnect.agent.runtime.capabilities.guard;

import java.util.List;

import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.hook.TurnInput;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Input guards: runs the {@link InputGuard}s that hold for the session before a turn starts. */
public final class InputGuardPlugin implements Plugin {

    @Override
    public String name() {
        return "input-guards";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.before(RuntimeHook.TURN_INPUT, input -> {
            TurnInput current = input;
            List<InputGuard> guards = registry.all(GuardResolver.class).stream()
                    .flatMap(resolver -> resolver.inputGuards(input.session()).stream())
                    .toList();
            for (InputGuard guard : guards) {
                TurnAdmission admission = guard.check(current);
                if (admission instanceof TurnAdmission.Admitted admitted) {
                    current = new TurnInput(current.session(), admitted.request());
                } else {
                    return BeforeOutcome.stop(admission);
                }
            }
            return BeforeOutcome.proceed(current);
        });
    }
}
