package ai.mindconnect.mail.view;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The name of a view — what a URL carries and a store files it under.
 *
 * <p>Three spellings, and the spelling is the whole point: a folder's view
 * is named after the folder ({@code f/email.freemail/INBOX}), all inboxes
 * after nothing ({@code all}), so both can be opened without a record of
 * them ever having been saved. A view that exists only because somebody
 * made it — what an agent gathered — gets a name of its own
 * ({@code s/4711}) and lives in the store.
 *
 * <p>The parts are URL-encoded on the way in, so a folder with a slash in
 * its name ({@code Kunden/2026}) survives the trip.
 */
public record ViewId(String value) {

    public ViewId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("A view id cannot be blank.");
    }

    public static ViewId of(String value) {
        return new ViewId(value);
    }

    /** The view of one folder of one mailbox. */
    public static ViewId folder(String account, String folderId) {
        return new ViewId("f/" + encode(account) + "/" + encode(folderId));
    }

    /** The inbox of every connected mailbox, merged. */
    public static ViewId allInboxes() {
        return new ViewId("all");
    }

    /** A view that only exists in the store — a fresh name for a new one. */
    public static ViewId saved() {
        return new ViewId("s/" + UUID.randomUUID());
    }

    public boolean isFolder() {
        return value.startsWith("f/");
    }

    public boolean isAllInboxes() {
        return "all".equals(value);
    }

    public boolean isSaved() {
        return value.startsWith("s/");
    }

    /** The account a folder view names; null for the other kinds. */
    public String account() {
        if (!isFolder()) return null;
        int slash = value.indexOf('/', 2);
        return decode(slash < 0 ? value.substring(2) : value.substring(2, slash));
    }

    /** The folder a folder view names; null for the other kinds. */
    public String folderId() {
        if (!isFolder()) return null;
        int slash = value.indexOf('/', 2);
        return slash < 0 ? null : decode(value.substring(slash + 1));
    }

    private static String encode(String part) {
        return URLEncoder.encode(Objects.requireNonNull(part), StandardCharsets.UTF_8);
    }

    private static String decode(String part) {
        return URLDecoder.decode(part, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return value;
    }
}
