package ai.mindconnect.mail.imap;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sending one message over the account's SMTP server.
 *
 * <p>Separate from {@link Mailbox} because it is a separate server, separate
 * credentials and — for most users — not configured at all. An account without
 * {@code MC_SMTP_HOST} reads and does not write, and the tool says so instead
 * of failing somewhere inside a connect.
 */
public final class MailSender {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MailSender.class);

    private MailSender() { }

    /**
     * Sends the message and returns the address it went out as.
     *
     * @throws MailConfigurationException when this account cannot send, or an address is malformed
     * @throws MailAccessException        when the server refused it
     */
    public static String send(MailAccount account, List<String> to, List<String> cc,
                       String subject, String body, boolean html) {
        return send(account, to, cc, subject, body, html, List.of());
    }

    /** A file that goes with a message. */
    public record Attachment(String name, String contentType, byte[] data) { }

    /**
     * Sends the message with files attached and returns the address it went
     * out as.
     *
     * @throws MailConfigurationException when this account cannot send, or an address is malformed
     * @throws MailAccessException        when the server refused it
     */
    public static String send(MailAccount account, List<String> to, List<String> cc,
                       String subject, String body, boolean html, List<Attachment> attachments) {
        if (!account.canSend()) {
            throw new MailConfigurationException("That mailbox is read-only: it has no SMTP server, so "
                    + "nothing can be sent from it. Add one to the connection under Connections in your "
                    + "profile.");
        }
        if (to.isEmpty()) {
            throw new MailConfigurationException("A message needs at least one recipient in \"to\".");
        }
        Session session = Session.getInstance(account.smtpProperties(), new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.smtpUser(), account.smtpPassword());
            }
        });
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(account.from()));
            message.setRecipients(Message.RecipientType.TO, parse(to));
            if (!cc.isEmpty()) {
                message.setRecipients(Message.RecipientType.CC, parse(cc));
            }
            message.setSubject(subject == null ? "" : subject, StandardCharsets.UTF_8.name());
            if (attachments == null || attachments.isEmpty()) {
                if (html) {
                    message.setContent(body == null ? "" : body, "text/html; charset=UTF-8");
                } else {
                    message.setText(body == null ? "" : body, StandardCharsets.UTF_8.name());
                }
            } else {
                message.setContent(mixed(body, html, attachments));
            }
            message.setSentDate(new java.util.Date());
            Transport.send(message);
            keepCopy(account, message);
            return account.from();
        } catch (AddressException e) {
            throw new MailConfigurationException("\"" + e.getRef() + "\" is not an e-mail address.");
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        }
    }

    /** The text first, then one part per file — what every client understands as "attached". */
    private static MimeMultipart mixed(String body, boolean html, List<Attachment> attachments)
            throws MessagingException {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        if (html) {
            text.setContent(body == null ? "" : body, "text/html; charset=UTF-8");
        } else {
            text.setText(body == null ? "" : body, StandardCharsets.UTF_8.name());
        }
        mixed.addBodyPart(text);
        for (Attachment attachment : attachments) {
            MimeBodyPart file = new MimeBodyPart();
            String type = attachment.contentType() == null || attachment.contentType().isBlank()
                    ? "application/octet-stream" : attachment.contentType();
            file.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment.data(), type)));
            try {
                file.setFileName(MimeUtility.encodeText(attachment.name(), StandardCharsets.UTF_8.name(), null));
            } catch (java.io.UnsupportedEncodingException e) {
                file.setFileName(attachment.name());
            }
            file.setDisposition(Part.ATTACHMENT);
            mixed.addBodyPart(file);
        }
        return mixed;
    }

    /**
     * A copy in the sent folder, as every mail client keeps one. Gmail and
     * Microsoft file what goes through their SMTP by themselves, so a copy
     * there would be a second one. A copy that cannot be kept is logged and
     * nothing more: the message has gone, and saying "not sent" would be a lie.
     */
    private static void keepCopy(MailAccount account, MimeMessage message) {
        if (account.isPop3() || filesItsOwnCopy(account.smtpHost())) return;
        try {
            Mailbox.saveSent(account, message);
        } catch (RuntimeException e) {
            log.warn("Sent, but no copy kept for {}: {}", account.from(), e.getMessage());
        }
    }

    /** SMTP servers that put a copy of what they send into the sent folder themselves. */
    static boolean filesItsOwnCopy(String smtpHost) {
        if (smtpHost == null) return false;
        String host = smtpHost.toLowerCase(java.util.Locale.ROOT);
        return host.endsWith("gmail.com") || host.endsWith("googlemail.com")
                || host.endsWith("office365.com") || host.endsWith("outlook.com")
                || host.endsWith("hotmail.com") || host.endsWith("live.com");
    }

    private static InternetAddress[] parse(List<String> addresses) throws AddressException {
        InternetAddress[] parsed = new InternetAddress[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            parsed[i] = new InternetAddress(addresses.get(i).strip(), true);
        }
        return parsed;
    }

    private static String explain(MailAccount account, MessagingException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (e instanceof jakarta.mail.AuthenticationFailedException || lower.contains("authentication")) {
            return "The SMTP server refused " + account.smtpUser() + ". Open the mailbox under Connections "
                    + "and check the SMTP account and password — when they are empty the mailbox account is "
                    + "used, which some providers do not accept for sending.";
        }
        if (lower.contains("connect") || lower.contains("timed out") || lower.contains("unknown host")) {
            return "Could not reach " + account.smtpHost() + ":" + account.smtpPort() + ". Open the mailbox "
                    + "under Connections and check the SMTP server, its port and its encryption — port 465 "
                    + "wants TLS from the first byte, port 587 wants STARTTLS.";
        }
        return "The SMTP server said: " + message;
    }
}
