package ai.mindconnect.agent.runtime.capabilities.memory;

import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Memory: keeps what the model is sent within its context. It works on the
 * request of one model call and never on the session — the session keeps
 * every message, so nothing is lost and a different strategy can be tried
 * on the same history.
 *
 * <p>Stage one only: trimming old tool results. Summarising follows and
 * will hand its work to {@code BackgroundWork}.
 */
public final class MemoryPlugin implements Plugin {

    private final int historyBudget;
    private final int keepRecentToolResults;

    public MemoryPlugin(int historyBudget, int keepRecentToolResults) {
        this.historyBudget = historyBudget;
        this.keepRecentToolResults = keepRecentToolResults;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.before(RuntimeHook.MODEL_CALL, call -> {
            ToolResultTrimmer trimmer = new ToolResultTrimmer(
                    registry.get(TokenCounter.class), historyBudget, keepRecentToolResults);
            ModelRequest request = call.request();
            return BeforeOutcome.proceed(call.with(new ModelRequest(request.turnId(), request.settings(),
                    request.instructions(), trimmer.trim(request.messages()), request.tools())));
        });
    }
}
