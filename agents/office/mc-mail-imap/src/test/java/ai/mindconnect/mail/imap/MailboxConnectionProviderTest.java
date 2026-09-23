package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The module declares the mailbox card and brings no tools of its own any more — mail_… does the work. */
class MailboxConnectionProviderTest {

    @Test
    void declares_the_mailbox_card_and_no_tools() {
        MailboxConnectionProvider provider = new MailboxConnectionProvider();

        assertThat(provider.toolNames()).isEmpty();
        assertThat(provider.connectionSpec()).isNotNull();
        assertThat(provider.connectionSpec().provider()).isEqualTo(MailAccount.PROVIDER);
        assertThat(provider.connectionTester()).isPresent();
        assertThat(provider.create("email_list_messages", AgentTool.of("email_list_messages"), null)).isEmpty();
    }
}
