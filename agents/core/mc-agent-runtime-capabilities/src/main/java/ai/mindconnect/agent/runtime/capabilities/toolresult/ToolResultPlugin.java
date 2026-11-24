package ai.mindconnect.agent.runtime.capabilities.toolresult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Keeps a tool's result to a size a model can read and a session can carry.
 *
 * <p>The model is shown a result one page at a time: past the agent's limit
 * it reads the beginning and a line saying how much there is and how to
 * read on, with {@value #FETCH_TOOL}. The session keeps the result whole, so
 * nothing is lost by that and whoever opens the chat sees all of it. The
 * limit is the agent's ({@value #MAX_RESULT_CHARS} in its metadata), and a
 * tool known to over-produce gets one of its own in the agent's binding.
 *
 * <p>What a session keeps has a cap of its own, the runtime's: a result past
 * it is cut before it is kept, and that cut is real loss — it keeps megabyte
 * dumps out of the store and the page.
 *
 * <p>Installed before memory: memory counts what the model is sent, and
 * that is the page, not the result.
 */
public final class ToolResultPlugin implements Plugin {

    /**
     * The key under which an agent says how many characters of one tool
     * result the model is shown at a time: in the agent's metadata for every
     * tool, in a tool binding's metadata for that tool.
     */
    public static final String MAX_RESULT_CHARS = "maxResultChars";

    /** The page of an agent that names none: what a model reads without losing the thread. */
    public static final int DEFAULT_PAGE_CHARS = 30_000;

    /** The cap for a runtime that names none: far above anything a model reads. */
    public static final int DEFAULT_MAX_RESULT_CHARS = 100_000;

    /** The tool that reads on in a result the model was shown a page of, or one memory has set aside. */
    public static final String FETCH_TOOL = "fetch_tool_result";

    private final int maxResultChars;

    /** @param maxResultChars the cap on what a session keeps of one result */
    public ToolResultPlugin(int maxResultChars) {
        if (maxResultChars <= 0) {
            throw new IllegalArgumentException("The cap on a tool result must be positive, not " + maxResultChars);
        }
        this.maxResultChars = maxResultChars;
    }

    /** How many characters of a result of this tool the agent's model is shown at a time. */
    static int pageChars(AgentDefinition agent, Object toolName) {
        Object ofTool = toolName instanceof String name
                ? agent.binding(name).map(binding -> binding.metadata().get(MAX_RESULT_CHARS)).orElse(null) : null;
        if (ofTool instanceof Number limit && limit.intValue() > 0) {
            return limit.intValue();
        }
        return agent.metadata().get(MAX_RESULT_CHARS) instanceof Number limit && limit.intValue() > 0
                ? limit.intValue() : DEFAULT_PAGE_CHARS;
    }

    @Override
    public String name() {
        return "tool-result";
    }

    @Override
    public void install(PluginRegistry registry) {
        Supplier<SessionStore> sessions = registry.supplier(SessionStore.class);
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(new FetchToolResultTool(sessions))));
        // Whoever has tools can be shown a page of a result, and has to be able to read on.
        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            if (context.agent().tools().isEmpty()
                    || tools.stream().anyMatch(tool -> FETCH_TOOL.equals(tool.name()))) {
                return tools;
            }
            List<ToolDefinition> offered = new ArrayList<>(tools);
            offered.add(FetchToolResultTool.DEFINITION);
            return offered;
        });
        registry.after(RuntimeHook.TOOL_CALL, this::kept);
        registry.before(RuntimeHook.MODEL_CALL, call -> BeforeOutcome.proceed(call.with(paged(call))));
    }

    private ToolOutput kept(ToolInvocation invocation, ToolOutput output) {
        if (!(output instanceof ToolOutput.Completed done) || done.text() == null
                || done.text().length() <= maxResultChars) {
            return output;
        }
        return new ToolOutput.Completed(done.text().substring(0, maxResultChars)
                + "\n… [output truncated after " + maxResultChars + " characters]", done.failed());
    }

    private static ModelRequest paged(ModelCall call) {
        ModelRequest request = call.request();
        AgentDefinition agent = call.context().agent();
        List<TurnMessage> shown = new ArrayList<>(request.messages());
        for (int i = 0; i < shown.size(); i++) {
            TurnMessage message = shown.get(i);
            Object tool = message.metadata().get("toolName");
            // What the fetch tool returned is a page already, and what memory shows as a note is none.
            if (message.type() != TurnMessage.Type.TOOL_RESULT || FETCH_TOOL.equals(tool)
                    || message.metadata().get(SessionStore.COMPRESSED) instanceof String) {
                continue;
            }
            int page = pageChars(agent, tool);
            String text = message.text();
            if (text.length() > page) {
                shown.set(i, new TurnMessage(message.id(), message.turnId(), message.type(),
                        List.of(new ContentPart.Text(text.substring(0, page) + readOn(message.id(), page, text.length()))),
                        message.toolCalls(), message.metadata()));
            }
        }
        return new ModelRequest(request.turnId(), request.settings(), request.instructions(), shown, request.tools());
    }

    /** The line under a page: how much there is, and how the rest is read. */
    static String readOn(String id, int shownTo, int length) {
        return "\n… [showing characters 0-" + shownTo + " of " + length + "."
                + (id == null ? "]" : " Read on with " + FETCH_TOOL + ": id=" + id + ", offset=" + shownTo + "]");
    }
}
