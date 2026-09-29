package ai.mindconnect.agent.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host names a request may address this server by — what the {@code Host}
 * header (HTTP/2: {@code :authority}) must say.
 *
 * <p>This is the defence against DNS rebinding: a web page at
 * {@code http://evil.example:9090} can point {@code evil.example} at
 * {@code 127.0.0.1} once the page is loaded, and from then on the browser
 * sends the page's requests to this server with {@code Host: evil.example:9090}
 * and {@code Origin: http://evil.example:9090}. CORS sees a same-origin request
 * and lets it through; only the host name gives the attack away. So a request
 * whose host is not in this list is refused before anything else looks at it.
 *
 * <p>A list is written as comma-separated entries: {@code host} (any port),
 * {@code host:port}, {@code [::1]} and {@code [::1]:9090} for IPv6, and
 * {@code *} for everything (no check). Host names compare case-insensitively
 * and without a trailing dot.
 *
 * <p>The {@link #loopback(String) default for a server without authentication}
 * is {@code localhost}, {@code 127.0.0.1}, {@code [::1]}, whatever
 * {@code server.address} names, and any IP address written as a literal: a
 * name can be rebound, an address cannot, so {@code Host: 192.168.1.20:9090}
 * from another machine on the LAN is still fine — whether the LAN should reach
 * an unauthenticated server is a question for {@code server.address}, not
 * for this list.
 */
public final class HostAllowlist {

    /** A host and the port it is allowed on; {@code -1} is every port. */
    record Entry(String host, int port) {
        boolean matches(String host, int port) {
            return this.host.equals(host) && (this.port == -1 || this.port == port);
        }
    }

    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
    /** What may stand between the brackets of an IPv6 literal, incl. a scope id ({@code fe80::1%en0}). */
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+(%[0-9a-zA-Z_.-]+)?");

    private static final HostAllowlist ANY = new HostAllowlist(List.of(), true, false);

    private final List<Entry> entries;
    private final boolean anyHost;
    private final boolean anyIpLiteral;

    private HostAllowlist(List<Entry> entries, boolean anyHost, boolean anyIpLiteral) {
        this.entries = List.copyOf(entries);
        this.anyHost = anyHost;
        this.anyIpLiteral = anyIpLiteral;
    }

    /** No check: every host is fine — a reverse proxy in front sets its own. */
    public static HostAllowlist any() {
        return ANY;
    }

    /**
     * The list an operator wrote: {@code localhost, 127.0.0.1:9090, [::1], app.example.com}.
     * A {@code *} anywhere in it means {@link #any()}.
     *
     * @throws IllegalArgumentException for an entry that is not a host, with or without a port
     */
    public static HostAllowlist of(String commaSeparated) {
        List<Entry> entries = new ArrayList<>();
        for (String raw : split(commaSeparated)) {
            if (raw.equals("*")) return ANY;
            entries.add(parseEntry(raw));
        }
        return new HostAllowlist(entries, false, false);
    }

    /**
     * The default while nothing authenticates callers: this machine's names for
     * itself, the address the server binds to, and every IP literal.
     *
     * @param serverAddress {@code server.address}; blank when the server listens on every interface
     */
    public static HostAllowlist loopback(String serverAddress) {
        List<Entry> entries = new ArrayList<>(List.of(
                new Entry("localhost", -1), new Entry("127.0.0.1", -1), new Entry(normalizeHost("[::1]"), -1)));
        if (serverAddress != null && !serverAddress.isBlank()) {
            entries.add(new Entry(normalizeHost(serverAddress.trim()), -1));
        }
        return new HostAllowlist(entries, false, true);
    }

    /** Whether nothing is checked. */
    public boolean isAny() {
        return anyHost;
    }

    /**
     * Whether a request carrying this {@code Host} header may proceed.
     *
     * @param hostHeader the header as sent, {@code null} when the request had none
     *                   (an HTTP/1.0 client — no browser, so nothing to defend against)
     * @param secure     whether the request came in over TLS, which decides the
     *                   port a header without one means
     */
    public boolean allows(String hostHeader, boolean secure) {
        if (anyHost) return true;
        if (hostHeader == null || hostHeader.isBlank()) return true;
        Optional<Authority> parsed = Authority.parse(hostHeader.trim());
        if (parsed.isEmpty()) return false;
        Authority authority = parsed.get();
        int port = authority.port() != -1 ? authority.port() : (secure ? 443 : 80);
        if (anyIpLiteral && isIpLiteral(authority.host())) return true;
        for (Entry entry : entries) {
            if (entry.matches(authority.host(), port)) return true;
        }
        return false;
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    /** A host and an optional port, as the Host header or a list entry writes them. */
    record Authority(String host, int port) {

        /** Empty when the text is not {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}. */
        static Optional<Authority> parse(String text) {
            String host;
            String portPart;
            if (text.startsWith("[")) {
                int close = text.indexOf(']');
                if (close < 0) return Optional.empty();
                host = text.substring(0, close + 1);
                String rest = text.substring(close + 1);
                if (rest.isEmpty()) {
                    portPart = null;
                } else if (rest.startsWith(":")) {
                    portPart = rest.substring(1);
                } else {
                    return Optional.empty();
                }
            } else {
                int colon = text.indexOf(':');
                if (colon < 0) {
                    host = text;
                    portPart = null;
                } else if (text.indexOf(':', colon + 1) >= 0) {
                    // A second colon: an IPv6 address without brackets is not a Host header.
                    return Optional.empty();
                } else {
                    host = text.substring(0, colon);
                    portPart = text.substring(colon + 1);
                }
            }
            if (host.isEmpty()) return Optional.empty();
            int port = -1;
            if (portPart != null) {
                if (portPart.isEmpty() || portPart.length() > 5 || !portPart.chars().allMatch(Character::isDigit)) {
                    return Optional.empty();
                }
                port = Integer.parseInt(portPart);
                if (port > 65535) return Optional.empty();
            }
            return Optional.of(new Authority(normalizeHost(host), port));
        }
    }

    private static Entry parseEntry(String raw) {
        return Authority.parse(raw)
                .map(a -> new Entry(a.host(), a.port()))
                .orElseThrow(() -> new IllegalArgumentException("Not a host name, with or without a port: \""
                        + raw + "\" — expected e.g. localhost, 127.0.0.1:9090, [::1] or app.example.com"));
    }

    private static List<String> split(String commaSeparated) {
        if (commaSeparated == null) return List.of();
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Lower case, no trailing dot ({@code localhost.} is {@code localhost}
     * written as a fully qualified name), and an IPv6 literal in the one form
     * Java prints it, so {@code [0:0:0:0:0:0:0:1]} and {@code [::1]} are the same.
     */
    static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            String inner = h.substring(1, h.length() - 1);
            // Only a literal reaches InetAddress: a name would trigger a DNS lookup.
            if (IPV6_CHARS.matcher(inner).matches()) {
                try {
                    return "[" + InetAddress.getByName(inner).getHostAddress() + "]";
                } catch (UnknownHostException ignored) {
                    // not a valid address — kept as written, it will match nothing
                }
            }
            return h;
        }
        if (h.endsWith(".") && h.length() > 1) h = h.substring(0, h.length() - 1);
        return h;
    }

    static boolean isIpLiteral(String normalizedHost) {
        return IPV4.matcher(normalizedHost).matches()
                || (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")
                    && IPV6_CHARS.matcher(normalizedHost.substring(1, normalizedHost.length() - 1)).matches());
    }

    @Override
    public String toString() {
        if (anyHost) return "*";
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(e.host());
            if (e.port() != -1) sb.append(':').append(e.port());
        }
        if (anyIpLiteral) sb.append(sb.isEmpty() ? "" : ", ").append("<any IP address>");
        return sb.toString();
    }
}
