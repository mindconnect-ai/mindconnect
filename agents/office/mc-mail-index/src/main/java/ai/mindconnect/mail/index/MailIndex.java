package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The window index, read through and written through.
 *
 * <p><b>Read through:</b> a folder somebody opens gets a window — its
 * newest {@link #window} heads, fetched once in pages — and from then on a
 * page of the folder comes from the window when the window reaches that
 * far, live from the provider when it does not (and the window grows to
 * there). A window older than {@link #ttl} is compared with the provider's
 * newest page before it answers: new heads come in, changed flags follow,
 * heads the provider no longer lists in that stretch go.
 *
 * <p><b>A search runs over the window</b>, all heads at once, with a
 * {@link Coverage} that says how far it reached — so an answer can say
 * "47 in the newest 1,000, back to 12 March; 2,835 older not searched"
 * instead of pretending the fifth page was the end.
 *
 * <p><b>Written through:</b> what the person deletes, moves or marks is
 * taken out of, moved between or changed in the windows at once; the next
 * comparison with the provider finds it already done.
 *
 * <p>Nothing here runs in the background: the index is only ever as fresh
 * as the last time somebody looked. That is deliberate for now — Concept
 * 42, step 3 is where the delta and the push come in.
 */
public final class MailIndex {

    /** The heads a window aims to hold when first filled. */
    public static final int WINDOW = 1_000;
    /** Past this a window is a mirror; what is looked at beyond the window is kept, the oldest go. */
    public static final int CAP = 2 * WINDOW;
    /** One provider page when filling or comparing. */
    public static final int PAGE = 200;
    /** How long a window answers without comparing its top with the provider. */
    public static final Duration TTL = Duration.ofMinutes(2);

    private final MailIndexStore store;
    private final Clock clock;
    private final int window;
    private final Duration ttl;

    public MailIndex(MailIndexStore store) {
        this(store, Clock.systemUTC(), WINDOW, TTL);
    }

    public MailIndex(MailIndexStore store, Clock clock, int window, Duration ttl) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Math.max(PAGE, window);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /** One page of a folder, and where it came from. */
    public record Slice(List<Fetched<MailMessage>> fetched, long total, boolean counted, Instant asOf) {
        public List<MailMessage> messages() {
            return fetched.stream().map(Fetched::value).toList();
        }
    }

    /** How far a search reached: {@code searched} of {@code total} heads, back to {@code oldestAt}. */
    public record Coverage(int searched, long total, Instant oldestAt) {
        public boolean whole() {
            return total >= 0 && searched >= total;
        }
        public long unsearched() {
            return total < 0 ? -1 : Math.max(0, total - searched);
        }
    }

    /** Matches in the window, newest first, with the coverage they rest on. */
    public record Found(List<MailMessage> matches, Coverage coverage) { }

    // ── reading ─────────────────────────────────────────────────────────────

    /**
     * The folder's window, filled or freshened as needed. {@code force}
     * compares with the provider whatever the age — the refresh button.
     */
    public FolderWindow window(UserId user, MailStore mail, Location at, boolean force) {
        FolderWindow current = store.load(user, at).orElse(null);
        Instant now = clock.instant();
        // None yet, or one that deletes have thinned below what the folder
        // still holds: filled whole. A window of 258 over a folder of 828
        // would answer "searched the newest 258" for no reason.
        if (current == null || current.counted() && current.size() < Math.min(window, current.total()) - PAGE) {
            FolderWindow filled = fill(mail, at, now);
            store.save(user, filled);
            return filled;
        }
        if (force || current.syncedAt().plus(ttl).isBefore(now)) {
            MailPage top = mail.list(at.folderId(), 0, PAGE, plain());
            FolderWindow fresh = current.merged(top.messages(), top.counted() ? top.total() : -1, now);
            store.save(user, fresh);
            return fresh;
        }
        return current;
    }

    /**
     * A page of the folder: from the window when it reaches that far, live
     * from the provider when it does not — and then into the window, which
     * grows to where somebody looked. With a query the page is a page of
     * the matches in the window.
     */
    public Slice page(UserId user, MailStore mail, Location at, int offset, int limit, MailQuery query) {
        FolderWindow w = window(user, mail, at, false);
        if (query != null && !query.isEmpty()) {
            List<MailMessage> matches = w.search(query);
            List<Fetched<MailMessage>> out = new ArrayList<>();
            for (MailMessage m : matches.stream().skip(offset).limit(limit).toList()) {
                out.add(new Fetched<>(m, w.syncedAt(), Fetched.Freshness.CACHED));
            }
            return new Slice(out, matches.size(), true, w.syncedAt());
        }
        if (w.covers(offset, limit)) {
            int end = Math.min(offset + limit, w.size());
            return new Slice(w.page(offset, end - offset), w.total(), w.counted(), w.syncedAt());
        }
        MailPage live = mail.list(at.folderId(), offset, limit, plain());
        FolderWindow grown = w.extended(live.messages(), live.counted() ? live.total() : w.total(), CAP);
        store.save(user, grown);
        return new Slice(live.fetched(), live.counted() ? live.total() : -1, live.counted(), live.asOf());
    }

    /** Everything in the window that matches, and how far that reaches. */
    public Found search(UserId user, MailStore mail, Location at, MailQuery query) {
        FolderWindow w = window(user, mail, at, false);
        return new Found(w.search(query), new Coverage(w.size(), w.total(), w.oldestAt()));
    }

    /**
     * The heads of these ids as the window has them — for a gathered list,
     * which reads its rows fresh each time. Ids the window does not hold
     * are left out; the caller asks the provider for those.
     */
    public Map<String, Fetched<MailMessage>> heads(UserId user, Location at, Collection<String> ids) {
        FolderWindow w = store.load(user, at).orElse(null);
        Map<String, Fetched<MailMessage>> out = new LinkedHashMap<>();
        if (w == null) return out;
        for (String id : ids) {
            MailMessage head = w.head(id);
            if (head != null) out.put(id, new Fetched<>(head, w.syncedAt(), Fetched.Freshness.CACHED));
        }
        return out;
    }

    /** Whether the folder has a window at all — nothing is read to find out. */
    public boolean has(UserId user, Location at) {
        return store.load(user, at).isPresent();
    }

    // ── writing through ─────────────────────────────────────────────────────

    /** These are gone from the folder — deleted, or moved away. */
    public void removed(UserId user, Location at, Collection<String> ids) {
        store.load(user, at).ifPresent(w -> store.save(user, w.without(ids)));
    }

    /** A message that arrived in {@code to} — by a move, under its new id — when {@code to} has a window. */
    public void arrived(UserId user, Location to, MailMessage head) {
        store.load(user, to).ifPresent(w -> store.save(user, w.with(head)));
    }

    /** Its read flag changed. */
    public void seen(UserId user, Location at, String id, boolean seen) {
        store.load(user, at).ifPresent(w -> {
            MailMessage head = w.head(id);
            if (head != null) store.save(user, w.replaced(head.withSeen(seen)));
        });
    }

    /** The window forgotten; the next look fills it again. */
    public void forget(UserId user, Location at) {
        store.delete(user, at);
    }

    // ── the fill ────────────────────────────────────────────────────────────

    /** The newest {@link #window} heads, a provider page at a time, newest first. */
    private FolderWindow fill(MailStore mail, Location at, Instant now) {
        List<MailMessage> heads = new ArrayList<>(window);
        long total = -1;
        boolean counted = true;
        int offset = 0;
        while (heads.size() < window) {
            MailPage page = mail.list(at.folderId(), offset, Math.min(PAGE, window - heads.size()), plain());
            if (page.counted()) total = page.total(); else counted = false;
            if (page.messages().isEmpty()) break;
            heads.addAll(page.messages());
            offset += page.messages().size();
            if (counted && offset >= total) break;
            if (page.messages().size() < PAGE) break;
        }
        return new FolderWindow(at, heads, counted ? total : -1, now, window);
    }

    private static MailQuery plain() {
        return MailQuery.of(false, null);
    }
}
