package ai.mindconnect.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * What became of one message in a call that changes where messages are —
 * {@link MailStore#move} and {@link MailStore#delete} answer with one of
 * these per id, in the order asked.
 *
 * <p>Per id, and not one answer for the batch, because the batch is what a
 * person ticked and the ids in it have different fates: one moved and got a
 * new id on the way (IMAP and Graph both do that), one was gone before the
 * call came, one the provider refused. A screen that keeps a list of
 * messages needs each of those separately — and a store that threw for the
 * whole batch, or quietly left the missing one out, told it nothing.
 */
public sealed interface Outcome {

    /** The id the call was asked about. */
    String id();

    /**
     * Moved to {@code to}. {@code newId} is the id it has there — the same as
     * before at Gmail, a new one at IMAP ({@code COPYUID}) and Graph — or
     * null when the server did not say, which an old IMAP server without
     * UIDPLUS will not.
     */
    record Moved(String id, Location to, String newId) implements Outcome { }

    /**
     * In the wastebasket. {@code handle} is what {@link MailStore#restore}
     * needs to bring it back — not always the id it had before.
     */
    record Deleted(String id, String handle) implements Outcome { }

    /** There was no such message any more when the call came. */
    record Gone(String id) implements Outcome { }

    /** The provider refused, and said why. */
    record Failed(String id, String why) implements Outcome { }

    /** The handles of everything {@link Deleted} — the receipt for one Undo. */
    static List<String> handles(List<Outcome> outcomes) {
        List<String> handles = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome instanceof Deleted d && d.handle() != null) handles.add(d.handle());
        }
        return handles;
    }

    /** How many were moved or deleted — what a toast counts. */
    static int done(List<Outcome> outcomes) {
        int n = 0;
        for (Outcome outcome : outcomes) {
            if (outcome instanceof Moved || outcome instanceof Deleted) n++;
        }
        return n;
    }

    /** The ids that were not there any more. */
    static List<String> gone(List<Outcome> outcomes) {
        List<String> gone = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome instanceof Gone g) gone.add(g.id());
        }
        return gone;
    }

    /** What went wrong, one sentence per failure; empty when nothing did. */
    static List<String> failures(List<Outcome> outcomes) {
        List<String> why = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome instanceof Failed f) why.add(f.why());
        }
        return why;
    }
}
