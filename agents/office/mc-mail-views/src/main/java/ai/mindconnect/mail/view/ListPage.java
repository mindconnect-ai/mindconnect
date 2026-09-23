package ai.mindconnect.mail.view;

import java.time.Instant;
import java.util.List;

/**
 * One page of a view.
 *
 * @param items   the rows, in the view's order
 * @param total   how many the view holds in all; -1 when nobody knows — a
 *                number nobody knows is not a zero
 * @param counted whether {@code total} is a count rather than an estimate
 * @param asOf    when this page was true
 * @param gone    rows the view had and could not find any more — taken out
 *                of the view already; the screen says so in a sentence
 */
public record ListPage(List<MailItem> items, long total, boolean counted, Instant asOf, List<String> gone) {

    public ListPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (total < 0) total = -1;
        asOf = asOf == null ? Instant.now() : asOf;
        gone = gone == null ? List.of() : List.copyOf(gone);
    }

    public static ListPage of(List<MailItem> items, long total, boolean counted, Instant asOf) {
        return new ListPage(items, total, counted, asOf, List.of());
    }

    public boolean hasTotal() {
        return total >= 0;
    }
}
