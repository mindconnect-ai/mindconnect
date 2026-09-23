package ai.mindconnect.mail.index;

import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The newest heads of one folder, newest first — what a person looks at,
 * and what a search runs over without asking the provider.
 *
 * <p>Not the folder: a window on it. Below the oldest head the folder goes
 * on, and {@link #total} says how far; a page past the window is read live
 * and slides the window down. The heads are {@link MailMessage}s as the
 * provider listed them (subject, sender, date, flags, a preview), never the
 * bodies.
 *
 * @param location  whose window this is
 * @param heads     newest first, no two with the same id
 * @param total     how many the folder holds, as the provider last said; -1 when it would not say
 * @param syncedAt  when the top of the window was last compared with the provider
 * @param filledTo  how many heads the first fill aimed at — the window's nominal size
 */
public record FolderWindow(Location location, List<MailMessage> heads, long total, Instant syncedAt, int filledTo) {

    public FolderWindow {
        Objects.requireNonNull(location, "location");
        heads = heads == null ? List.of() : List.copyOf(heads);
        syncedAt = syncedAt == null ? Instant.EPOCH : syncedAt;
    }

    public static FolderWindow empty(Location location, int filledTo) {
        return new FolderWindow(location, List.of(), -1, Instant.EPOCH, filledTo);
    }

    public int size() {
        return heads.size();
    }

    public boolean counted() {
        return total >= 0;
    }

    /** True when the whole folder is in the window — nothing lies below it. */
    public boolean complete() {
        return counted() && heads.size() >= total;
    }

    /** The date of the oldest head: how far back a search over this window reaches. */
    public Instant oldestAt() {
        return heads.isEmpty() ? null : heads.get(heads.size() - 1).receivedAt();
    }

    public boolean has(String id) {
        for (MailMessage head : heads) if (head.id().equals(id)) return true;
        return false;
    }

    public MailMessage head(String id) {
        for (MailMessage head : heads) if (head.id().equals(id)) return head;
        return null;
    }

    /** {@code limit} heads from {@code offset}, as cached — empty when the window does not reach that far. */
    public List<Fetched<MailMessage>> page(int offset, int limit) {
        if (offset < 0 || offset + limit > heads.size()) return List.of();
        List<Fetched<MailMessage>> out = new ArrayList<>(limit);
        for (MailMessage head : heads.subList(offset, offset + limit)) {
            out.add(new Fetched<>(head, syncedAt, Fetched.Freshness.CACHED));
        }
        return out;
    }

    /** Whether the window can serve that page without the provider. */
    public boolean covers(int offset, int limit) {
        return offset + limit <= heads.size() || complete() && offset < heads.size();
    }

    /**
     * The heads that match, newest first. {@code search} and {@code from}
     * are words looked for case-insensitively; {@code search} in the
     * subject, the sender and the preview, {@code from} in the sender,
     * {@code subject} in the subject.
     */
    public List<MailMessage> search(MailQuery query) {
        if (query == null || query.isEmpty()) return heads;
        List<MailMessage> out = new ArrayList<>();
        for (MailMessage head : heads) {
            if (query.unreadOnly() && head.seen()) continue;
            if (query.since() != null && (head.receivedAt() == null || head.receivedAt().isBefore(query.since()))) continue;
            if (query.before() != null && (head.receivedAt() == null || !head.receivedAt().isBefore(query.before()))) continue;
            if (!contains(head.from(), query.from())) continue;
            if (!contains(head.subject(), query.subject())) continue;
            if (query.search() != null && !contains(head.subject(), query.search())
                    && !contains(head.from(), query.search()) && !contains(head.body(), query.search())) continue;
            out.add(head);
        }
        return out;
    }

    private static boolean contains(String text, String word) {
        if (word == null || word.isBlank()) return true;
        return text != null && text.toLowerCase(Locale.ROOT).contains(word.strip().toLowerCase(Locale.ROOT));
    }

    // ── changing it ─────────────────────────────────────────────────────────

    /**
     * The provider's newest {@code fetched} laid over the window: a head the
     * provider still lists is replaced (flags move), one it lists for the
     * first time is inserted, and a head of the window that lies inside the
     * fetched stretch of time but is not in it any more is gone. Heads older
     * than the stretch stay as they were.
     */
    public FolderWindow merged(List<MailMessage> fetched, long total, Instant at) {
        if (fetched.isEmpty()) {
            return new FolderWindow(location, total == 0 ? List.of() : heads, total, at, filledTo);
        }
        Map<String, MailMessage> byId = new LinkedHashMap<>();
        for (MailMessage m : fetched) byId.put(m.id(), m);
        Instant oldestFetched = fetched.get(fetched.size() - 1).receivedAt();
        List<MailMessage> kept = new ArrayList<>(heads.size() + fetched.size());
        kept.addAll(fetched);
        for (MailMessage head : heads) {
            if (byId.containsKey(head.id())) continue;
            boolean inside = oldestFetched != null && head.receivedAt() != null && !head.receivedAt().isBefore(oldestFetched);
            if (inside) continue;   // the provider listed that stretch and this one was not in it
            kept.add(head);
        }
        kept.sort(NEWEST_FIRST);
        return new FolderWindow(location, kept, total, at, filledTo);
    }

    /** Heads read live below the window, appended; the window grows to where somebody looked. */
    public FolderWindow extended(List<MailMessage> older, long total, int cap) {
        if (older.isEmpty()) return new FolderWindow(location, heads, total, syncedAt, filledTo);
        Set<String> have = new HashSet<>();
        for (MailMessage head : heads) have.add(head.id());
        List<MailMessage> all = new ArrayList<>(heads);
        for (MailMessage m : older) if (have.add(m.id())) all.add(m);
        all.sort(NEWEST_FIRST);
        if (all.size() > cap) all = new ArrayList<>(all.subList(0, cap));
        return new FolderWindow(location, all, total, syncedAt, filledTo);
    }

    public FolderWindow without(Collection<String> ids) {
        Set<String> gone = new HashSet<>(ids);
        List<MailMessage> left = new ArrayList<>(heads.size());
        for (MailMessage head : heads) if (!gone.contains(head.id())) left.add(head);
        long n = counted() ? Math.max(0, total - (heads.size() - left.size())) : total;
        return new FolderWindow(location, left, n, syncedAt, filledTo);
    }

    /** One more head, in its place by date — a message that arrived here by a move. */
    public FolderWindow with(MailMessage head) {
        if (has(head.id())) return replaced(head);
        List<MailMessage> all = new ArrayList<>(heads);
        all.add(head);
        all.sort(NEWEST_FIRST);
        return new FolderWindow(location, all, counted() ? total + 1 : total, syncedAt, filledTo);
    }

    public FolderWindow replaced(MailMessage head) {
        List<MailMessage> all = new ArrayList<>(heads.size());
        for (MailMessage h : heads) all.add(h.id().equals(head.id()) ? head : h);
        return new FolderWindow(location, all, total, syncedAt, filledTo);
    }

    static final java.util.Comparator<MailMessage> NEWEST_FIRST = java.util.Comparator.comparing(
            (MailMessage m) -> m.receivedAt(), java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
}
