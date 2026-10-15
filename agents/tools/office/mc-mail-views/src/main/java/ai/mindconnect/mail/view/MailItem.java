package ai.mindconnect.mail.view;

import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row of a view: the message, and everything the view knows about it
 * that the message does not know about itself.
 *
 * <p>Two layers, because two parties know something. The store says how it
 * came by the message — {@link Fetched}: when, and whether from the provider
 * or a cache. The view says the rest: what the row is called on the screen,
 * whether it is ticked, and anything a kind of view wants to say about a
 * row in {@code extra} ("matched: sender").
 *
 * @param rowId      the row's name on the screen — {@link MailMessage.Ref#rowId()}
 * @param message    the message, as far as a row needs it
 * @param location   where it lies; the row carries it itself because it
 *                   changes when the message is moved
 * @param freshness  where the store got it
 * @param loadedAt   when the store got it
 * @param bodyCached true when opening it will not have to fetch the text
 * @param selected   ticked, as {@link ViewState#selected()} has it
 * @param extra      what the view adds
 */
public record MailItem(
        String rowId,
        MailMessage message,
        Location location,
        Fetched.Freshness freshness,
        Instant loadedAt,
        boolean bodyCached,
        boolean selected,
        Map<String, String> extra
) {

    public MailItem {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(loadedAt, "loadedAt");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** A row for what the store fetched, ticked or not. */
    public static MailItem of(Fetched<MailMessage> fetched, boolean selected) {
        MailMessage m = fetched.value();
        return new MailItem(m.ref().rowId(), m, m.location(), fetched.freshness(), fetched.at(),
                false, selected, Map.of());
    }

    public MailItem with(String key, String value) {
        Map<String, String> more = new java.util.LinkedHashMap<>(extra);
        more.put(key, value);
        return new MailItem(rowId, message, location, freshness, loadedAt, bodyCached, selected, more);
    }
}
