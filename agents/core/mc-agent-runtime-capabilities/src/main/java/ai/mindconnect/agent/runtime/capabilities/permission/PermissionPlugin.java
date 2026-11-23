package ai.mindconnect.agent.runtime.capabilities.permission;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Permissions: which tools a session sees and which calls wait for the user.
 * One set of rules, asked twice — when tools are offered, by name, and when
 * a tool is called. A tool being installed therefore says nothing about it
 * being usable.
 */
public final class PermissionPlugin implements Plugin {

    /**
     * The key in the metadata of an agent's tool entry that says a call of
     * this tool by this agent has to be approved: {@code true}. It makes an
     * allowed tool ask; a tool a rule denies stays denied.
     */
    public static final String NEEDS_APPROVAL = "needsApproval";

    private final PermissionEffect unruled;
    private final ApprovalMemory approvals;

    /**
     * @param unruled what holds for a tool no rule covers. This is the whole
     *                difference between a command line, where it is
     *                {@link PermissionEffect#ALLOW}, and a server, where a
     *                tool must be switched on and it is
     *                {@link PermissionEffect#DENY}
     */
    public PermissionPlugin(PermissionEffect unruled, ApprovalMemory approvals) {
        this.unruled = unruled;
        this.approvals = approvals;
    }

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(new AskUserTool())));

        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> tools.stream()
                .filter(tool -> effect(registry, tool.name(), context) != PermissionEffect.DENY)
                .toList());

        registry.before(RuntimeHook.TOOL_CALL, invocation ->
                switch (effect(registry, invocation.call().toolName(), invocation.context())) {
                    case ALLOW -> BeforeOutcome.proceed(invocation);
                    case DENY -> BeforeOutcome.stop(ToolOutput.failed(
                            "Error: " + invocation.call().toolName() + " is not permitted here"));
                    case ASK -> asked(invocation);
                });
    }

    private BeforeOutcome<ToolInvocation, ToolOutput> asked(ToolInvocation invocation) {
        Session session = invocation.context().session();
        String toolName = invocation.call().toolName();
        if (approvals.approved(session.id(), toolName)) {
            return BeforeOutcome.proceed(invocation);
        }
        if (invocation.answer().isEmpty()) {
            return BeforeOutcome.stop(ToolOutput.interrupt(Interruption.TOOL_APPROVAL,
                    Map.of("tool", toolName, "arguments", invocation.call().arguments())));
        }
        Map<String, Object> answer = invocation.answer().get().answer();
        if (!Boolean.TRUE.equals(answer.get(InterruptionAnswer.APPROVED))) {
            Object note = answer.get(InterruptionAnswer.TEXT);
            return BeforeOutcome.stop(ToolOutput.failed(
                    "The user did not approve this call" + (note == null ? "." : ": " + note)));
        }
        if (Boolean.TRUE.equals(answer.get(InterruptionAnswer.REMEMBER))) {
            approvals.remember(session.id(), toolName);
        }
        return BeforeOutcome.proceed(invocation);
    }

    private PermissionEffect effect(PluginRegistry registry, String toolName, AgentContext context) {
        PermissionEffect ruled = registry.all(PermissionRuleSource.class).stream()
                .flatMap(source -> source.rules(context.session()).stream())
                .filter(rule -> rule.covers(toolName))
                .map(PermissionRule::effect)
                .findFirst()
                .orElse(unruled);
        boolean agentAsks = context.agent().binding(toolName)
                .map(binding -> Boolean.TRUE.equals(binding.metadata().get(NEEDS_APPROVAL)))
                .orElse(false);
        return ruled == PermissionEffect.ALLOW && agentAsks ? PermissionEffect.ASK : ruled;
    }
}
