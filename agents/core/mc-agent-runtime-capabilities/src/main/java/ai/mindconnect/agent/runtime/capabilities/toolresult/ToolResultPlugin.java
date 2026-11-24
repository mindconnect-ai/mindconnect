package ai.mindconnect.agent.runtime.capabilities.toolresult;

import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Keeps a tool's result to a size a session can carry, at both ends of its
 * life. When it is produced, a result past its limit is cut before it is
 * kept: a tool known to over-produce gets a limit of its own in the agent's
 * binding, and no result grows past the cap of the runtime, whatever the
 * agent says — that one keeps megabyte dumps out of the store and the page.
 * The cut shows in the text, so the model and the reader know it happened.
 *
 * <p>Later, when memory shows an old result as a note to make the history
 * fit, the result itself is still in the session. An agent that binds
 * {@value #FETCH_TOOL} reads it again by the id the note names.
 *
 * <p>The cut is lossy on purpose and the note is not: what was cut here was
 * never kept, what memory set aside is all still there.
 */
public final class ToolResultPlugin implements Plugin {

    /** The key in a tool binding's metadata: how many characters a result of this tool may have for this agent. */
    public static final String MAX_RESULT_CHARS = "maxResultChars";

    /** The cap for a runtime that names none: far above anything a model reads. */
    public static final int DEFAULT_MAX_RESULT_CHARS = 100_000;

    /** The tool that reads again a result memory has set aside. */
    public static final String FETCH_TOOL = "fetch_tool_result";

    private final int maxResultChars;

    /** @param maxResultChars the cap no result passes, whatever a binding allows */
    public ToolResultPlugin(int maxResultChars) {
        if (maxResultChars <= 0) {
            throw new IllegalArgumentException("The cap on a tool result must be positive, not " + maxResultChars);
        }
        this.maxResultChars = maxResultChars;
    }

    @Override
    public String name() {
        return "tool-result";
    }

    @Override
    public void install(PluginRegistry registry) {
        Supplier<SessionStore> sessions = registry.supplier(SessionStore.class);
        // Found by an agent that names it, like any tool: the offer starts from what the agent binds.
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(new FetchToolResultTool(sessions))));
        registry.after(RuntimeHook.TOOL_CALL, this::cut);
    }

    private ToolOutput cut(ToolInvocation invocation, ToolOutput output) {
        if (!(output instanceof ToolOutput.Completed done) || done.text() == null) {
            return output;
        }
        int limit = invocation.context().agent().binding(invocation.call().toolName())
                .map(binding -> binding.metadata().get(MAX_RESULT_CHARS))
                .filter(Number.class::isInstance)
                .map(allowed -> ((Number) allowed).intValue())
                .filter(allowed -> allowed > 0)
                .map(allowed -> Math.min(allowed, maxResultChars))
                .orElse(maxResultChars);
        if (done.text().length() <= limit) {
            return output;
        }
        return new ToolOutput.Completed(done.text().substring(0, limit)
                + "\n… [output truncated after " + limit + " characters]", done.failed());
    }
}
