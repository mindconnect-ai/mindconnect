package ai.mindconnect.calendar.caldav;

import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.schema.Schema;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * A CalDAV account: a URL, a user and a password.
 *
 * <p>CalDAV is the calendar what IMAP is to mail — the standard nearly every
 * provider speaks beside its own API, and the one an operator can run
 * themselves (Nextcloud, Radicale, SOGo). A mail provider's calendar is
 * usually a URL of the shape {@code https://caldav.example.com/dav/<user>/}.
 *
 * <p>The URL may be the collection of one calendar or the home that holds
 * several; {@link CalDavCalendarStore#calendars()} asks the server which it is.
 */
public record CalDavAccount(URI url, String user, String password) {

    /** What a connection of this kind is stored under. */
    public static final String PROVIDER = "caldav";

    public static final String URL = "url";
    public static final String USER = "user";
    public static final String PASSWORD = "password";

    public CalDavAccount {
        Objects.requireNonNull(url, "url");
    }

    public static Schema schema() {
        return Schema.object()
                .prop(URL, Schema.string()
                        .description("The CalDAV address of your calendars — your provider's help page calls "
                                + "it the CalDAV server or URL, e.g. https://caldav.example.com/dav/you@example.com/. "
                                + "The address of one calendar works too."))
                .prop(USER, Schema.string()
                        .description("The account you sign in with — usually your e-mail address."))
                .prop(PASSWORD, Schema.string().format(Schema.Format.PASSWORD)
                        .description("Where your provider offers app-specific passwords, use one of those "
                                + "rather than your own."))
                .require(URL, USER, PASSWORD);
    }

    /** The card and the form, as the Connections page renders them. */
    public static ConnectionSpec connectionSpec() {
        return ConnectionSpec.form(PROVIDER, "Calendar (CalDAV)", schema())
                .description("Calendars over CalDAV — what most mail providers and every self-hosted "
                        + "calendar speak. Connect two and each is a calendar account of its own.")
                .icon("calendar")
                .allowingSeveral();
    }

    /**
     * The account behind one connection.
     *
     * @throws CalDavException when a value is missing or the URL is not one —
     *         the form requires all three, so this is a safety net
     */
    public static CalDavAccount from(ToolConnection connection) {
        Objects.requireNonNull(connection, "connection");
        String url = value(connection, URL);
        String user = value(connection, USER);
        String password = value(connection, PASSWORD);
        if (url == null || user == null || password == null) {
            throw new CalDavException("Your calendar \"" + connection.label() + "\" is missing its "
                    + (url == null ? "address" : user == null ? "user" : "password")
                    + ". Open it under Connections in your profile.");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new CalDavException("\"" + url + "\" is not a web address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new CalDavException("The calendar address has to start with https://.");
        }
        if (scheme.equals("http") && !nearby(uri.getHost())) {
            throw new CalDavException("The calendar address has to start with https:// — over http:// the "
                    + "password would cross the internet readable to anyone on the way. Plain http:// is "
                    + "only accepted for a server on this machine or the local network.");
        }
        return new CalDavAccount(uri, user, password);
    }

    /**
     * True for a host plain {@code http://} is acceptable for: this machine,
     * a private or link-local address, or a name that only a local network
     * resolves ({@code nas}, {@code radicale.local}, {@code ….home.arpa}).
     * Every request carries the password in Basic form, which without TLS is
     * as good as plain text — fine between a server and the Radicale next to
     * it, not across the internet. The name is judged as written; it is not
     * looked up.
     */
    static boolean nearby(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, name.length() - 1);
        if (name.equals("localhost") || name.endsWith(".localhost")) return true;
        if (name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".internal")
                || name.endsWith(".home.arpa")) {
            return true;
        }
        boolean literal = name.contains(":") || name.matches("[0-9.]+");
        if (!literal) return !name.contains(".");
        try {
            InetAddress address = InetAddress.getByName(name);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address instanceof java.net.Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The Authorization header value — CalDAV servers take Basic over TLS. */
    public String authorization() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String value(ToolConnection connection, String field) {
        String value = connection.value(field);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
