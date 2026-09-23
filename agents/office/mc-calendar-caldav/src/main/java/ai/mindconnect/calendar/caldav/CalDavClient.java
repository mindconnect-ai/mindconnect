package ai.mindconnect.calendar.caldav;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CalDAV over the JDK's HTTP client: the four verbs a calendar needs
 * ({@code PROPFIND}, {@code REPORT}, {@code PUT}, {@code DELETE}) and
 * {@code GET}.
 *
 * <p>No WebDAV library. CalDAV is HTTP with two XML bodies, and a library for
 * it would bring an XML stack, a connection pool and its own idea of
 * authentication for what fits on a page.
 *
 * <p><b>Errors are sentences.</b> A refused password, a URL that is not a
 * calendar and a server that will not answer each say what to do about it —
 * never the raw body, which on a failing server is a page of HTML.
 */
public class CalDavClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;

    public CalDavClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public CalDavClient(HttpClient http) {
        this.http = http;
    }

    /** A {@code PROPFIND} at {@code depth} — what is here, and what is under it. */
    public String propfind(CalDavAccount account, URI url, int depth, String body) {
        return send(account, request(account, url)
                .header("Depth", String.valueOf(depth))
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /** A {@code REPORT} on one calendar — the query that returns its events. */
    public String report(CalDavAccount account, URI url, String body) {
        return send(account, request(account, url)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("REPORT", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /** One resource — an {@code .ics} file. */
    public String get(CalDavAccount account, URI url) {
        return fetch(account, url).body();
    }

    /**
     * One resource with the {@code ETag} it has on the server right now — what
     * a later write hands back as {@code If-Match}, so that it fails rather
     * than overwrites when somebody changed the file in between.
     */
    public Resource fetch(CalDavAccount account, URI url) {
        HttpResponse<String> response = exchange(account, request(account, url).GET().build());
        return new Resource(url, response.headers().firstValue("ETag").orElse(null),
                response.body() == null ? "" : response.body());
    }

    /** Writes an {@code .ics}: a new appointment, or a new version of one. */
    public void put(CalDavAccount account, URI url, String ical) {
        put(account, url, ical, null);
    }

    /**
     * Writes an {@code .ics} only if it is still the version {@code etag}
     * names; the server answers 412 otherwise. A null {@code etag} writes
     * unconditionally.
     */
    public void put(CalDavAccount account, URI url, String ical, String etag) {
        HttpRequest.Builder request = request(account, url)
                .header("Content-Type", "text/calendar; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(ical, StandardCharsets.UTF_8));
        if (etag != null && !etag.isBlank()) request.header("If-Match", etag);
        send(account, request.build());
    }

    public void delete(CalDavAccount account, URI url) {
        delete(account, url, null);
    }

    /** Removes a resource only if it is still the version {@code etag} names, when one is given. */
    public void delete(CalDavAccount account, URI url, String etag) {
        HttpRequest.Builder request = request(account, url).DELETE();
        if (etag != null && !etag.isBlank()) request.header("If-Match", etag);
        send(account, request.build());
    }

    /** An {@code .ics} as it is on the server: where, which version, and what it says. */
    public record Resource(URI url, String etag, String body) { }

    // ── internals ───────────────────────────────────────────────────────────

    private HttpRequest.Builder request(CalDavAccount account, URI url) {
        return HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Authorization", account.authorization())
                .header("User-Agent", "mindconnect-caldav");
    }

    private String send(CalDavAccount account, HttpRequest request) {
        HttpResponse<String> response = exchange(account, request);
        return response.body() == null ? "" : response.body();
    }

    private HttpResponse<String> exchange(CalDavAccount account, HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CalDavException("Could not reach " + host(request.uri()) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CalDavException("Interrupted while talking to " + host(request.uri()), e);
        }
        if (response.statusCode() >= 400) {
            throw explain(account, response.statusCode(), request.uri());
        }
        return response;
    }

    private CalDavException explain(CalDavAccount account, int status, URI url) {
        String message = switch (status) {
            case 401, 403 -> "The calendar server refused the sign-in for "
                    + account.user() + ". Many providers want an app-specific password here; open the "
                    + "connection under Connections in your profile and check it.";
            case 404 -> "There is no calendar at " + url.getPath()
                    + ". Check the CalDAV address on your provider's help page.";
            case 405 -> "The server does not allow that here — the address is probably "
                    + "not a calendar but a page above it.";
            case 412 -> "The appointment changed on the server while this was writing. "
                    + "Read it again and repeat the change.";
            case 429, 503 -> "The calendar server is asking us to slow down. "
                    + "Try again in a moment.";
            default -> "The calendar server refused (HTTP " + status + ").";
        };
        return new CalDavException(message, status);
    }

    private static String host(URI url) {
        return url.getHost() == null ? url.toString() : url.getHost();
    }

    /** The bodies this client sends, kept where the requests are. */
    public static final class Bodies {

        private Bodies() { }

        /** What a collection is and what it is called — enough to tell a calendar from a folder. */
        public static String calendars() {
            return """
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                xmlns:cs="http://apple.com/ns/ical/">
                      <d:prop>
                        <d:resourcetype/>
                        <d:displayname/>
                        <d:current-user-privilege-set/>
                        <c:supported-calendar-component-set/>
                        <cs:calendar-color/>
                      </d:prop>
                    </d:propfind>
                    """;
        }

        /**
         * The appointments of one calendar that touch a period, with their data.
         *
         * <p>{@code expand} asks the server to turn a recurring appointment
         * into the occurrences that fall into the period (RFC 4791, 9.6.5):
         * without it the answer is the series' first VEVENT with its rule,
         * and a weekly meeting that began last year would be listed on the
         * day it began.
         */
        public static String eventsBetween(String from, String to) {
            return """
                    <?xml version="1.0" encoding="utf-8"?>
                    <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                      <d:prop>
                        <d:getetag/>
                        <c:calendar-data>
                          <c:expand start="%s" end="%s"/>
                        </c:calendar-data>
                      </d:prop>
                      <c:filter>
                        <c:comp-filter name="VCALENDAR">
                          <c:comp-filter name="VEVENT">
                            <c:time-range start="%s" end="%s"/>
                          </c:comp-filter>
                        </c:comp-filter>
                      </c:filter>
                    </c:calendar-query>
                    """.formatted(from, to, from, to);
        }

        /**
         * The resource that holds the appointment with this {@code UID} — for
         * servers whose file names are not the UID (Apple, Outlook, DAVx5 all
         * pick their own).
         */
        public static String eventByUid(String uid) {
            return """
                    <?xml version="1.0" encoding="utf-8"?>
                    <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                      <d:prop>
                        <d:getetag/>
                        <c:calendar-data/>
                      </d:prop>
                      <c:filter>
                        <c:comp-filter name="VCALENDAR">
                          <c:comp-filter name="VEVENT">
                            <c:prop-filter name="UID">
                              <c:text-match collation="i;octet">%s</c:text-match>
                            </c:prop-filter>
                          </c:comp-filter>
                        </c:comp-filter>
                      </c:filter>
                    </c:calendar-query>
                    """.formatted(escape(uid));
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }

    /** {@code PROPFIND} and {@code REPORT} answer with one {@code response} per resource. */
    public static Map<String, String> responses(String xml, String tag) {
        Map<String, String> byHref = new LinkedHashMap<>();
        if (xml == null) return byHref;
        for (String response : between(xml, "response")) {
            String href = first(between(response, "href"));
            if (href == null || href.isBlank()) continue;
            String value = first(between(response, tag));
            byHref.put(href.strip(), value == null ? "" : value);
        }
        return byHref;
    }

    /** The bodies of every {@code <ns:tag>…</ns:tag>} in {@code xml}, whatever the namespace prefix. */
    public static java.util.List<String> between(String xml, String tag) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (xml == null) return found;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(?:[A-Za-z0-9_.-]+:)?" + tag + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?" + tag + ">",
                        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        while (m.find()) found.add(unescape(m.group(1)));
        return found;
    }

    /** True when {@code xml} carries an empty element of that name, e.g. {@code <c:calendar/>}. */
    public static boolean has(String xml, String tag) {
        return xml != null && java.util.regex.Pattern
                .compile("<(?:[A-Za-z0-9_.-]+:)?" + tag + "(?:\\s[^>]*)?/?>",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(xml).find();
    }

    private static String first(java.util.List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#13;", "\r").replace("&amp;", "&");
    }
}
