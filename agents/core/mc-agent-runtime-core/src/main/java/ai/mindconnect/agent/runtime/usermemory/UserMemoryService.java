package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way into the users' memory — for the tools, the profile's and the
 * admin's pages and the REST API. Keeps the entries small enough to live in
 * a system prompt: a name that is also a file name, a one-line description,
 * a bounded content and a bounded number of entries per user.
 *
 * <p>A user's memory has two kinds of entries: their own, which every agent
 * with the memory tools shares ({@code agentId} {@code null}), and what one
 * agent keeps about them ({@code agentId} that agent's). Which ones a tool
 * works on is the binding's {@link MemoryReach}.
 *
 * <p>Thread-safe. Writes and deletes of one user run one at a time: a model
 * may call {@code memory_delete} and {@code memory_write} on the same name in
 * one round, and the tools run in parallel — without the lock a write could
 * report "updated" for an entry the delete removed a moment later, or bring
 * it back. The lock is per process; it does not order writers on two nodes.
 */
public class UserMemoryService {

    /** Longest name, after normalising. */
    public static final int MAX_NAME = 64;
    /** Longest description: it stands in every system prompt. */
    public static final int MAX_DESCRIPTION = 200;
    /** Longest content: an entry is a fact, not a document. */
    public static final int MAX_CONTENT = 4_000;
    /** Most entries a user may have, their own and every agent's together; past it a write asks to clean up first. */
    public static final int MAX_ENTRIES = 200;

    /** What a write did: the entry as stored, and whether it is new. */
    public record Written(MemoryEntry entry, boolean created) { }

    private final UserMemoryRepository repository;
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public UserMemoryService(UserMemoryRepository repository) {
        this.repository = repository;
    }

    /** All of the user's entries — their own and every agent's — most recently changed first. */
    public List<MemoryEntry> list(UserId userId) {
        return repository.findByUser(userId).stream().sorted(RECENT_FIRST).toList();
    }

    /**
     * What an agent sees of the user's memory under the given reach: the
     * user's own entries, the agent's own, or both — most recently changed
     * first. Without an agent there is no agent memory to see.
     */
    public List<MemoryEntry> list(UserId userId, AgentId agentId, MemoryReach reach) {
        return repository.findByUser(userId).stream()
                .filter(e -> e.shared() ? reach.includesShared()
                        : reach.includesAgent() && e.agentId().equals(agentId))
                .sorted(RECENT_FIRST)
                .toList();
    }

    /** Every entry of every user in the namespace, by user and then most recently changed first — for an admin. */
    public List<MemoryEntry> listAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing((MemoryEntry e) -> e.userId().value()).thenComparing(RECENT_FIRST))
                .toList();
    }

    /** An entry of the user's own memory ({@code agentId} {@code null}) or of one agent's. */
    public Optional<MemoryEntry> read(UserId userId, AgentId agentId, String name) {
        return repository.find(userId, agentId, normaliseName(name));
    }

    /** An entry of the user's own memory. */
    public Optional<MemoryEntry> read(UserId userId, String name) {
        return read(userId, null, name);
    }

    private static final Comparator<MemoryEntry> RECENT_FIRST =
            Comparator.comparing(MemoryEntry::updatedAt).reversed().thenComparing(MemoryEntry::name);

    /**
     * Creates the entry, or replaces the one of the same name — keeping when
     * it was first written. Without content, the description is the content. Throws {@link IllegalArgumentException} with a
     * message the model can act on when a field is out of bounds.
     */
    public Written write(UserId userId, String name, MemoryType type, String description, String content,
                         SessionId sourceSessionId) {
        return write(userId, null, name, type, description, content, sourceSessionId);
    }

    /** The same, in the memory {@code agentId} names — {@code null} for the user's own. */
    public Written write(UserId userId, AgentId agentId, String name, MemoryType type, String description,
                         String content, SessionId sourceSessionId) {
        if (userId == null) throw new IllegalArgumentException("a memory needs a user");
        if (type == null) throw new IllegalArgumentException("'type' is required");
        String key = normaliseName(name);
        String line = oneLine(description);
        if (line.isEmpty()) throw new IllegalArgumentException("'description' is required — one line saying what the memory is");
        if (line.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("'description' is " + line.length() + " characters; keep it to "
                    + MAX_DESCRIPTION + " and put the rest into 'content'");
        }
        // A fact that fits the description needs no content of its own — small models leave it empty.
        String body = content == null || content.isBlank() ? line : content.strip();
        if (body.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("'content' is " + body.length() + " characters; keep one memory to "
                    + MAX_CONTENT + " — split it, or keep only what will matter later");
        }
        synchronized (lockFor(userId)) {
            Optional<MemoryEntry> existing = repository.find(userId, agentId, key);
            if (existing.isEmpty() && repository.findByUser(userId).size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException("this user already has " + MAX_ENTRIES + " memories — update or "
                        + "delete an outdated one instead of adding another");
            }
            Instant now = Instant.now();
            Instant created = existing.map(MemoryEntry::createdAt).orElse(now);
            MemoryEntry saved = repository.save(
                    new MemoryEntry(userId, agentId, key, type, line, body, sourceSessionId, created, now));
            return new Written(saved, existing.isEmpty());
        }
    }

    /** {@code false} when there was nothing of that name. */
    public boolean delete(UserId userId, String name) {
        return delete(userId, null, name);
    }

    /** The same, in the memory {@code agentId} names — {@code null} for the user's own. */
    public boolean delete(UserId userId, AgentId agentId, String name) {
        String key = normaliseName(name);
        synchronized (lockFor(userId)) {
            return repository.delete(userId, agentId, key);
        }
    }

    private Object lockFor(UserId userId) {
        return locks.computeIfAbsent(userId, u -> new Object());
    }

    /**
     * Lower case, German umlauts spelt out, runs of anything but letters
     * and digits folded into one hyphen, hyphens trimmed: {@code "User Role"}
     * and {@code "user_role"} are both {@code user-role}, {@code "Größe"} is
     * {@code groesse}. The name becomes a file name in the file store, so
     * nothing else gets through.
     */
    public static String normaliseName(String name) {
        if (name == null) throw new IllegalArgumentException("'name' is required");
        String key = name.strip().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("'name' must contain letters or digits, e.g. 'preferred-language'");
        }
        if (key.length() > MAX_NAME) {
            throw new IllegalArgumentException("'name' is longer than " + MAX_NAME + " characters");
        }
        return key;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }
}
