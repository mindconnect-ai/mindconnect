package ai.mindconnect.agent.tool;

import java.util.List;
import java.util.Objects;

/**
 * What a tool source says it needs a user to connect.
 *
 * <p>Declared, not read — like {@link ToolFactory#userVariables()} and for the
 * same reason. This says <em>that</em> a mailbox is needed and what a form for
 * it looks like; <em>whose</em> mailbox is answered per call, from the
 * connections of whoever the call runs for.
 *
 * <p>A declaration is all the generic machinery needs: the Verbindungen page
 * renders the card and the form from it, the sign-in check turns it into
 * "nothing connected yet", and the binding decorator turns
 * {@link #params()} into schema properties.
 *
 * @param provider     the key connections are stored under — {@code "email"},
 *                     {@code "microsoft"}. <b>Not the tool group:</b> three
 *                     bundles (mail, calendar, files) can share one Microsoft
 *                     connection, and that is the point of keeping them apart
 * @param title        what the card is called: "E-Mail-Postfach"
 * @param description  one line under it; null when the title says it
 * @param icon         a lucide name for the card; null for the default
 * @param acquisitions how a user comes to have one — at least one way
 * @param params       which ends of this source's tools must be pointed at an
 *                     account; usually exactly one, {@link ConnectionParam#account}
 * @param multiple     whether a user may attach several. False means one
 *                     account, and the parameter never appears
 */
public record ConnectionSpec(
        String provider,
        String title,
        String description,
        String icon,
        List<Acquisition> acquisitions,
        List<ConnectionParam> params,
        boolean multiple
) {

    public ConnectionSpec {
        Objects.requireNonNull(provider, "A connection spec needs a provider");
        if (title == null || title.isBlank()) title = provider;
        acquisitions = acquisitions == null ? List.of() : List.copyOf(acquisitions);
        params = params == null || params.isEmpty()
                ? List.of(ConnectionParam.account(provider))
                : List.copyOf(params);
    }

    /** A provider with one account per user, filled in by hand. */
    public static ConnectionSpec form(String provider, String title, ai.mindconnect.schema.Schema schema) {
        return new ConnectionSpec(provider, title, null, null,
                List.of(Acquisition.Form.of(schema)), null, false);
    }

    /** The same, but the user may attach several accounts — two mailboxes, private and work. */
    public ConnectionSpec allowingSeveral() {
        return new ConnectionSpec(provider, title, description, icon, acquisitions, params, true);
    }

    public ConnectionSpec description(String text) {
        return new ConnectionSpec(provider, title, text, icon, acquisitions, params, multiple);
    }

    public ConnectionSpec icon(String name) {
        return new ConnectionSpec(provider, title, description, name, acquisitions, params, multiple);
    }

    /** Adds another way to come by a connection — OAuth beside the form. */
    public ConnectionSpec acquire(Acquisition acquisition) {
        List<Acquisition> ways = new java.util.ArrayList<>(acquisitions);
        ways.add(acquisition);
        return new ConnectionSpec(provider, title, description, icon, ways, params, multiple);
    }

    /** Replaces the connection parameters — for a tool source with two ends. */
    public ConnectionSpec params(ConnectionParam... connectionParams) {
        return new ConnectionSpec(provider, title, description, icon, acquisitions,
                List.of(connectionParams), multiple);
    }

    /** The form, if this provider can be filled in by hand. */
    public java.util.Optional<Acquisition.Form> form() {
        return acquisitions.stream()
                .filter(Acquisition.Form.class::isInstance).map(Acquisition.Form.class::cast)
                .findFirst();
    }

    /**
     * True when the connection is chosen per call rather than pinned: more
     * than one end can never be folded away, and a single end only stays
     * hidden while the user has at most one connection.
     */
    public boolean choosePerCall() {
        return params.size() > 1;
    }
}
