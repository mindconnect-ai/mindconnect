package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.schema.Schema;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * One mailbox the user connected, as this module needs it.
 *
 * <p>Read per call from the {@link ToolConnection} the runtime bound to that
 * call. A user may have two — "Privat" and "Arbeit" — and which one this is
 * was decided before the tool was entered, by the account parameter or by
 * their default. Nothing here knows or cares which.
 *
 * @param protocol     {@code imap} or {@code pop3}
 * @param id           the mailbox id the connection opens as — {@code email.freemail} — what
 *                     every message read here is stamped with as its {@link ai.mindconnect.mail.Location}
 * @param host         the mail server
 * @param port         its port
 * @param ssl          TLS from the first byte (imaps/pop3s)
 * @param user         the account to sign in as
 * @param password     its password, or an app-specific one
 * @param folder       the folder the tools work in unless a call names another
 * @param smtpHost     where to send through; null when this mailbox only reads
 * @param smtpPort     its port
 * @param smtpSsl      TLS from the first byte, as port 465 expects
 * @param smtpStartTls upgrade after the greeting, as port 587 expects
 * @param smtpUser     the SMTP account; the reading account when unset
 * @param smtpPassword its password; the reading password when unset
 * @param from         the address to send as; the SMTP account when unset
 */
public record MailAccount(
        String id,
        String protocol,
        String host,
        int port,
        boolean ssl,
        String user,
        String password,
        String folder,
        String smtpHost,
        int smtpPort,
        boolean smtpSsl,
        boolean smtpStartTls,
        String smtpUser,
        String smtpPassword,
        String from
) {

    /** What a connection of this kind is stored under. */
    public static final String PROVIDER = "email";

    // ── the fields a user fills in ──────────────────────────────────────────

    public static final String HOST = "host";
    public static final String PROTOCOL = "protocol";
    public static final String PORT = "port";
    public static final String SSL = "ssl";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String FOLDER = "folder";
    public static final String SMTP_HOST = "smtpHost";
    public static final String SMTP_PORT = "smtpPort";
    public static final String SMTP_SSL = "smtpSsl";
    public static final String SMTP_STARTTLS = "smtpStartTls";
    public static final String SMTP_USER = "smtpUser";
    public static final String SMTP_PASSWORD = "smtpPassword";
    public static final String FROM = "from";

    /** How long to wait for the server, in milliseconds — a tool call must not hang a turn. */
    private static final String TIMEOUT_MS = "30000";

    /**
     * What a user fills in to connect a mailbox. This is the whole user
     * interface of this module: the Admin UI renders the form from it, decides
     * what is encrypted from {@code Format.PASSWORD}, and stores what comes
     * back. No screen ships with this jar.
     */
    public static Schema schema() {
        return Schema.object()
                .prop(HOST, Schema.string()
                        .description("The IMAP or POP3 server of your mailbox, e.g. imap.gmail.com."))
                .prop(USER, Schema.string()
                        .description("The account you sign in with — usually your e-mail address."))
                .prop(PASSWORD, Schema.string().format(Schema.Format.PASSWORD)
                        .description("Where your provider offers app-specific passwords, use one of those "
                                + "rather than your own."))
                .prop(PROTOCOL, Schema.enumOf("imap", "pop3").defaultValue("imap")
                        .description("imap has folders, flags and server-side search; pop3 has the inbox."))
                .prop(PORT, Schema.integer().defaultValue(993)
                        .description("993 for IMAP over TLS, 995 for POP3 over TLS, 143 or 110 without."))
                .prop(SSL, Schema.bool().defaultValue(true)
                        .description("TLS from the first byte, as ports 993 and 995 expect."))
                .prop(FOLDER, Schema.string().defaultValue("INBOX")
                        .description("The folder the tools read when a call names none."))
                .prop(SMTP_HOST, Schema.string()
                        .description("Where to send through, e.g. smtp.gmail.com. Leave it empty and this "
                                + "mailbox is read-only."))
                .prop(SMTP_PORT, Schema.integer().defaultValue(587)
                        .description("587 with STARTTLS, 465 with TLS from the first byte."))
                .prop(SMTP_STARTTLS, Schema.bool().defaultValue(true)
                        .description("Upgrade the connection after the greeting, as port 587 expects."))
                .prop(SMTP_SSL, Schema.bool().defaultValue(false)
                        .description("TLS from the first byte, as port 465 expects."))
                .prop(SMTP_USER, Schema.string()
                        .description("Only when sending signs in as somebody else than reading."))
                .prop(SMTP_PASSWORD, Schema.string().format(Schema.Format.PASSWORD)
                        .description("Only when the SMTP account differs from the mailbox account."))
                .prop(FROM, Schema.string()
                        .description("The address your messages are sent as; the SMTP account when empty."))
                .require(HOST, USER, PASSWORD);
    }

    /** The card and the form, as the Connections page renders them. */
    public static ConnectionSpec connectionSpec() {
        return ConnectionSpec.form(PROVIDER, "Mailbox", schema())
                .description("A mailbox the email tools read, over IMAP or POP3, and send through over SMTP. "
                        + "Connect two and you can have one tool per mailbox.")
                .icon("mail")
                .allowingSeveral();
    }

    /**
     * The account behind one connection.
     *
     * @throws MailConfigurationException when a value reading needs is missing
     *         or malformed — the form requires the three that matter, so this
     *         is a safety net rather than the usual path
     */
    public static MailAccount from(ToolConnection connection) {
        Objects.requireNonNull(connection, "connection");
        String host = value(connection, HOST);
        String user = value(connection, USER);
        String password = value(connection, PASSWORD);
        if (host == null || user == null || password == null) {
            throw new MailConfigurationException("Your mailbox \"" + connection.label() + "\" is missing "
                    + missing(host, user, password) + ". Open it under Connections in your profile.");
        }
        String protocol = lower(orDefault(value(connection, PROTOCOL), "imap"));
        if (!protocol.equals("imap") && !protocol.equals("pop3")) {
            throw new MailConfigurationException("The protocol of \"" + connection.label() + "\" is \""
                    + protocol + "\"; it has to be imap or pop3.");
        }
        boolean ssl = flag(value(connection, SSL), true);
        int port = number(connection, PORT, ssl ? (protocol.equals("imap") ? 993 : 995)
                                                : (protocol.equals("imap") ? 143 : 110), "port");
        return new MailAccount(ai.mindconnect.mail.ConnectedMailbox.idOf(connection), protocol, host, port, ssl, user, password,
                orDefault(value(connection, FOLDER), "INBOX"),
                value(connection, SMTP_HOST),
                number(connection, SMTP_PORT, 587, "SMTP port"),
                flag(value(connection, SMTP_SSL), false),
                flag(value(connection, SMTP_STARTTLS), true),
                orDefault(value(connection, SMTP_USER), user),
                orDefault(value(connection, SMTP_PASSWORD), password),
                orDefault(value(connection, FROM), orDefault(value(connection, SMTP_USER), user)));
    }

    /** True when this mailbox can send as well as read. */
    public boolean canSend() {
        return smtpHost != null && !smtpHost.isBlank();
    }

    /** The store protocol Jakarta Mail is asked for: {@code imaps} over TLS, {@code imap} without. */
    public String storeProtocol() {
        return ssl ? protocol + "s" : protocol;
    }

    /** POP3 has one folder and no flags; several tools say so rather than failing at the server. */
    public boolean isPop3() {
        return "pop3".equals(protocol);
    }

    /** Connection properties for reading — timeouts bounded, so a dead server ends the call, not the turn. */
    public Properties storeProperties() {
        Properties props = new Properties();
        String p = storeProtocol();
        props.setProperty("mail.store.protocol", p);
        props.setProperty("mail." + p + ".host", host);
        props.setProperty("mail." + p + ".port", String.valueOf(port));
        props.setProperty("mail." + p + ".connectiontimeout", TIMEOUT_MS);
        props.setProperty("mail." + p + ".timeout", TIMEOUT_MS);
        props.setProperty("mail." + p + ".writetimeout", TIMEOUT_MS);
        if (!ssl) {
            // Plain port, but upgrade where the server offers it: a password
            // must not travel in the clear just because 143 was configured.
            props.setProperty("mail." + p + ".starttls.enable", "true");
        }
        return props;
    }

    /** Connection properties for sending. */
    public Properties smtpProperties() {
        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.host", smtpHost);
        props.setProperty("mail.smtp.port", String.valueOf(smtpPort));
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.setProperty("mail.smtp.timeout", TIMEOUT_MS);
        props.setProperty("mail.smtp.writetimeout", TIMEOUT_MS);
        // Three cases, said apart rather than inferred from the port: TLS from
        // the first byte (465), an upgrade after the greeting (587), or a relay
        // that offers neither — which only a test server or an internal hop is.
        if (smtpSsl) {
            props.setProperty("mail.smtp.ssl.enable", "true");
        } else if (smtpStartTls) {
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
        }
        return props;
    }

    private static String missing(String host, String user, String password) {
        StringBuilder names = new StringBuilder();
        if (host == null) names.append("its server");
        if (user == null) names.append(names.isEmpty() ? "" : ", ").append("its account");
        if (password == null) names.append(names.isEmpty() ? "" : ", ").append("its password");
        return names.toString();
    }

    private static String value(ToolConnection connection, String field) {
        String value = connection.value(field);
        if (value == null) return null;
        String text = value.strip();
        return text.isEmpty() ? null : text;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean flag(String value, boolean fallback) {
        if (value == null) return fallback;
        return switch (lower(value)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static int number(ToolConnection connection, String field, int fallback, String what) {
        String value = value(connection, field);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MailConfigurationException("The " + what + " of \"" + connection.label() + "\" is \""
                    + value + "\"; it has to be a number.");
        }
    }

    /** The password never travels into a log or a tool result. */
    @Override
    public String toString() {
        return "MailAccount[" + user + "@" + host + ":" + port + " " + storeProtocol()
                + (canSend() ? ", smtp " + smtpHost + ":" + smtpPort : ", read-only") + "]";
    }
}
