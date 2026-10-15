package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ConnectionTest;
import ai.mindconnect.agent.tool.ConnectionTester;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.util.Optional;
import java.util.Set;

/**
 * The IMAP mailbox card, and nothing else.
 *
 * <p>The {@code email_…} tools are gone; the Office tools ({@code mail_…})
 * read and change every mailbox, IMAP included, through the same code the
 * mail screen uses. What stays here is what only this module can say: the
 * form a mailbox is connected with ({@link MailAccount}) and how the Test
 * button signs in to it. A provider with no tool names still declares its
 * connection, which is what puts the card on every user's profile.
 */
public class MailboxConnectionProvider implements MultiToolProvider {

    /** The group the card is filed under, as it always was. */
    static final String GROUP = "email";

    @Override public String group() { return GROUP; }

    @Override public Set<String> toolNames() { return Set.of(); }

    @Override public ConnectionSpec connectionSpec() { return MailAccount.connectionSpec(); }

    /** Signs in to the mailbox and, when it can send, to SMTP. */
    @Override
    public Optional<ConnectionTester> connectionTester() {
        return Optional.of(connection -> {
            try {
                return Mailbox.probe(MailAccount.from(connection));
            } catch (MailConfigurationException e) {
                return ConnectionTest.failed(e.getMessage());
            }
        });
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        return Optional.empty();
    }
}
