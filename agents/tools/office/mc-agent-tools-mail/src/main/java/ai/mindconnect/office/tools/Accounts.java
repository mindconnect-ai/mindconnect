package ai.mindconnect.office.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The accounts of one kind a user has — mailboxes, calendars, address books
 * — and what an {@code account} argument means among them.
 *
 * <p>An account is named {@code provider.key} ({@code microsoft.work},
 * {@code email.freemail}), as the Office screens name it; a bare key is
 * accepted when it is unambiguous. {@code all} means every usable one, where a
 * tool offers it. No argument means the only account there is, and is refused
 * when there are several — guessing which mailbox to send from is not a
 * default.
 */
public record Accounts<A>(List<A> accounts, Function<A, String> id, Function<A, String> key,
                   Function<A, String> describe, Function<A, Boolean> usable) {

    public static final String ALL = "all";

    /** The ids a schema offers, "all" first where it applies. */
    public List<String> choices(boolean withAll) {
        List<String> out = new ArrayList<>();
        if (withAll && accounts.size() > 1) out.add(ALL);
        for (A account : accounts) if (usable.apply(account)) out.add(id.apply(account));
        return out;
    }

    /** The accounts an argument names: one, or every usable one for "all". */
    public List<A> pick(Map<String, Object> args, boolean allowAll) {
        String wanted = OfficeTool.str(args, "account");
        List<A> live = accounts.stream().filter(usable::apply).toList();
        if (live.isEmpty()) {
            throw new OfficeTool.Refused("No account of this kind is connected. The user connects one on their "
                    + "profile page, under Connections.");
        }
        if (wanted == null) {
            if (live.size() == 1) return live;
            if (allowAll) return live;
            throw new OfficeTool.Refused("Several accounts are connected; say which with \"account\": "
                    + String.join(", ", choices(false)) + ".");
        }
        if (ALL.equalsIgnoreCase(wanted)) {
            if (!allowAll) throw new OfficeTool.Refused("This tool works on one account; name it with \"account\".");
            return live;
        }
        for (A account : live) if (id.apply(account).equals(wanted)) return List.of(account);
        List<A> byKey = live.stream().filter(a -> key.apply(a).equals(wanted)).toList();
        if (byKey.size() == 1) return byKey;
        throw new OfficeTool.Refused("There is no account \"" + wanted + "\". The connected ones are: "
                + String.join(", ", choices(false)) + ".");
    }

    /** Exactly one account. */
    public A one(Map<String, Object> args) {
        return pick(args, false).get(0);
    }

    /** One line per account, for the list tools. */
    public String describeAll() {
        StringBuilder out = new StringBuilder();
        for (A account : accounts) {
            out.append("- ").append(id.apply(account)).append(": ").append(describe.apply(account))
                    .append(usable.apply(account) ? "" : " (needs to be connected again)").append('\n');
        }
        return out.toString();
    }
}
