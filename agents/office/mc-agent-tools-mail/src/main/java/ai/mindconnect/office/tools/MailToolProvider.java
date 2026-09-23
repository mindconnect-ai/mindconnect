package ai.mindconnect.office.tools;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.index.MailIndex;
import ai.mindconnect.mail.view.CurrentView;
import ai.mindconnect.mail.view.MailListViews;

import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/**
 * The mail tools: one set for every mailbox a user connected, whatever kind
 * it is — {@code mail_folders}, {@code mail_list}, {@code mail_read},
 * {@code mail_mark_read}, {@code mail_move}, {@code mail_delete},
 * {@code mail_send}.
 *
 * <p>A call names its mailbox as {@code provider.key} ({@code email.work}),
 * or asks {@code all} where a listing can span them. Which kinds exist is
 * {@link MailAccounts}' business: IMAP with this module's neighbour on the
 * classpath, and whatever other {@link ai.mindconnect.mail.MailProvider}s a
 * distribution adds — the tools, their names and their arguments stay the
 * same.
 *
 * <p>Not {@code ConnectedTool}s: a connection parameter belongs to one
 * provider and these span every kind. Each tool resolves the account among
 * the calling user's own connections, so a call can reach nothing else.
 *
 * <p>Changing has tool names of its own — approval is set per tool name on an
 * agent's binding, so {@code mail_send} can ask first while {@code mail_list}
 * runs freely.
 */
public class MailToolProvider implements MultiToolProvider {

    private MailTools mail;
    /** The list tools — only with a host that keeps views; a bare runtime has the mail tools alone. */
    private MailListTools lists;

    @Override
    public Set<String> toolNames() {
        if (lists == null) return MailTools.NAMES;
        Set<String> all = new java.util.LinkedHashSet<>(MailTools.NAMES);
        all.addAll(MailListTools.NAMES);
        return all;
    }

    @Override
    public String group() {
        return "office";
    }

    @Override
    public String subgroup(String toolName) {
        return "Mail";
    }

    /** The host's own {@link MailAccounts} when it has one, else one on the runtime's connections. */
    @Override
    public void bind(ToolEnvironment env) {
        MailAccounts accounts = env.get(MailAccounts.class)
                .orElseGet(() -> env.get(Connections.class).map(MailAccounts::new).orElse(null));
        bind(accounts, env.get(MailListViews.class).orElse(null),
                env.get(CurrentView.class).orElseGet(CurrentView.Memory::new),
                env.get(MailIndex.class).orElse(null));
    }

    /** The registry directly — for a host without an environment, and for tests. */
    public MailToolProvider bind(MailAccounts accounts) {
        return bind(accounts, null, null);
    }

    /** With the views as well: the list tools appear beside the mail tools. */
    public MailToolProvider bind(MailAccounts accounts, MailListViews views, CurrentView current) {
        return bind(accounts, views, current, null);
    }

    /** With the window index: {@code mail_list} searches it and says how far that reached. */
    public MailToolProvider bind(MailAccounts accounts, MailListViews views, CurrentView current, MailIndex index) {
        this.mail = accounts == null ? null : new MailTools(accounts, ZoneId.systemDefault(), index);
        this.lists = accounts == null || views == null ? null
                : new MailListTools(accounts, views, current == null ? new CurrentView.Memory() : current, index);
        return this;
    }

    @Override
    public boolean isAvailable() {
        return mail != null;
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        if (mail == null || scope == null || scope.userId() == null) return Optional.empty();
        if (lists != null && MailListTools.NAMES.contains(toolName)) {
            return lists.create(toolName, scope.userId(),
                    scope.sessionId() == null ? null : scope.sessionId().value());
        }
        return mail.create(toolName, scope.userId());
    }
}
