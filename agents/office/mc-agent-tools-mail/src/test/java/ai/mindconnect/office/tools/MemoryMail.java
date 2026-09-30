package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailBody;
import ai.mindconnect.mail.MailDraft;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailProvider;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailStoreException;
import ai.mindconnect.mail.Outcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A copy of the views module's test fixture, with read flags that stick.
 *
 * <p>Mailboxes in memory, behind the real {@link MailAccounts}: a provider
 * called {@code memory}, one box per key, folders of messages. Enough of the
 * port to page, search, move and delete — and a move hands out a new id, the
 * way IMAP and Graph do, because that is the case a list has to survive.
 */
final class MemoryMail {

    static final String PROVIDER = "memory";
    static final UserId ME = UserId.of("me");

    static final class Box {
        final Map<String, List<MailMessage>> folders = new LinkedHashMap<>();
        int next = 1;

        Box() {
            folders.put("INBOX", new ArrayList<>());
            folders.put("Archiv", new ArrayList<>());
            folders.put("Trash", new ArrayList<>());
        }
    }

    final Map<String, Box> boxes = new LinkedHashMap<>();

    MemoryMail box(String key) {
        boxes.computeIfAbsent(key, k -> new Box());
        return this;
    }

    /** A message into a folder; the id it got. */
    String deliver(String key, String folder, String subject, String from, Instant at) {
        Box box = boxes.get(key);
        String id = String.valueOf(box.next++);
        box.folders.get(folder).add(new MailMessage(id, new Location(PROVIDER + "." + key, folder), subject, from,
                List.of("me@example.com"), at, false, false, List.of(), null, false));
        return id;
    }

    /** Take one away behind the screen's back — deleted in another client. */
    void vanish(String key, String folder, String id) {
        boxes.get(key).folders.get(folder).removeIf(m -> m.id().equals(id));
    }

    MailAccounts accounts() {
        return new MailAccounts(connections(), List.of(provider()));
    }

    private Connections connections() {
        return new Connections() {
            @Override public List<ToolConnection> of(UserId user, String provider) {
                if (!PROVIDER.equals(provider)) return List.of();
                return boxes.keySet().stream().map(MemoryMail::connection).toList();
            }
            @Override public Optional<ToolConnection> resolve(UserId user, String provider, String key) {
                return PROVIDER.equals(provider) && boxes.containsKey(key)
                        ? Optional.of(connection(key)) : Optional.empty();
            }
        };
    }

    private static ToolConnection connection(String key) {
        return new ToolConnection() {
            @Override public String key() { return key; }
            @Override public String label() { return key; }
            @Override public String provider() { return PROVIDER; }
            @Override public String value(String field) { return null; }
            @Override public boolean usable() { return true; }
        };
    }

    private MailProvider provider() {
        return new MailProvider() {
            @Override public String provider() { return PROVIDER; }
            @Override public MailStore open(ToolConnection connection) {
                return new Store(boxes.get(connection.key()), PROVIDER + "." + connection.key());
            }
        };
    }

    static final class Store implements MailStore {
        private final Box box;
        private final String account;

        Store(Box box, String account) {
            this.box = box;
            this.account = account;
        }

        @Override public List<MailFolder> folders() {
            return box.folders.keySet().stream().map(name -> MailFolder.of(name, name)).toList();
        }

        private List<MailMessage> in(String folderId) {
            List<MailMessage> messages = box.folders.get(folderId);
            if (messages == null) throw new MailStoreException("There is no folder \"" + folderId + "\".");
            return messages;
        }

        @Override public MailPage list(String folderId, int skip, int limit, MailQuery query) {
            List<MailMessage> hits = in(folderId).stream()
                    .filter(m -> !query.unreadOnly() || !m.seen())
                    .filter(m -> query.search() == null
                            || m.subject().toLowerCase().contains(query.search().toLowerCase())
                            || m.from().toLowerCase().contains(query.search().toLowerCase()))
                    .sorted(Comparator.comparing(MailMessage::receivedAt).reversed())
                    .toList();
            return MailPage.of(hits.stream().skip(skip).limit(limit).toList(), hits.size());
        }

        @Override public List<MailMessage> summaries(String folderId, List<String> ids) {
            return in(folderId).stream().filter(m -> ids.contains(m.id())).toList();
        }

        @Override public MailMessage read(String folderId, String id) {
            return in(folderId).stream().filter(m -> m.id().equals(id)).findFirst()
                    .orElseThrow(() -> new MailStoreException("There is no message " + id + "."));
        }

        @Override public MailBody body(String folderId, String id) { return MailBody.plain(read(folderId, id).body()); }
        @Override public void setSeen(String folderId, String id, boolean seen) {
            in(folderId).replaceAll(m -> m.id().equals(id) ? m.withSeen(seen) : m);
        }
        @Override public boolean canOrganise() { return true; }

        @Override public List<Outcome> delete(String folderId, List<String> ids) {
            List<Outcome> out = new ArrayList<>();
            for (Outcome o : move(folderId, ids, "Trash")) {
                out.add(o instanceof Outcome.Moved m ? new Outcome.Deleted(m.id(), m.newId()) : o);
            }
            return out;
        }

        @Override public void restore(String folderId, List<String> handles) { move("Trash", handles, folderId); }

        /** A moved message gets a new id, as at IMAP and Graph. */
        @Override public List<Outcome> move(String folderId, List<String> ids, String target) {
            List<Outcome> out = new ArrayList<>();
            List<MailMessage> from = in(folderId);
            List<MailMessage> to = in(target);
            for (String id : ids) {
                MailMessage found = from.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
                if (found == null) { out.add(new Outcome.Gone(id)); continue; }
                from.remove(found);
                String newId = String.valueOf(box.next++);
                Location there = new Location(account, target);
                to.add(new MailMessage(newId, there, found.subject(), found.from(), found.to(), found.receivedAt(),
                        found.seen(), false, List.of(), found.body(), false));
                out.add(new Outcome.Moved(id, there, newId));
            }
            return out;
        }

        @Override public boolean canSend() { return false; }
        @Override public String send(MailDraft draft) { throw new MailStoreException("Memory mail cannot send."); }
        @Override public void close() { }
    }
}
