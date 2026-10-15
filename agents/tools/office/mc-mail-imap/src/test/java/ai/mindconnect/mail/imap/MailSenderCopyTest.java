package ai.mindconnect.mail.imap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailSenderCopyTest {

    @Test
    void the_providers_that_file_their_own_copy_get_no_second_one() {
        assertThat(MailSender.filesItsOwnCopy("smtp.gmail.com")).isTrue();
        assertThat(MailSender.filesItsOwnCopy("smtp.office365.com")).isTrue();
        assertThat(MailSender.filesItsOwnCopy("smtp-mail.outlook.com")).isTrue();
        // web.de, GMX, a company's own server: the copy is the client's job.
        assertThat(MailSender.filesItsOwnCopy("smtp.web.de")).isFalse();
        assertThat(MailSender.filesItsOwnCopy("mail.example.com")).isFalse();
        assertThat(MailSender.filesItsOwnCopy(null)).isFalse();
    }
}
