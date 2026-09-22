package ai.mindconnect.calendar;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The calendar accounts one user has attached, and how to open one.
 *
 * <p>Which kinds exist is not decided here: every {@link CalendarProvider} on
 * the classpath brings one. With the CalDAV module alone that is any CalDAV
 * server — a mail provider's calendar, a Nextcloud, a Radicale; with the
 * hosted modules beside it, Outlook and Google Calendar appear under the same
 * ids and the same tools.
 *
 * <p>An account is named {@code provider.key} — {@code caldav.web},
 * {@code microsoft.work} — as the screens and the tools name it.
 */
public class CalendarAccounts {

    private final Connections connections;
    private final Map<String, CalendarProvider> providers;

    /** Every provider the classpath brings. */
    public CalendarAccounts(Connections connections) {
        this(connections, ServiceLoader.load(CalendarProvider.class).stream()
                .map(ServiceLoader.Provider::get).toList());
    }

    /** The providers given — for a host that builds them itself, and for tests. */
    public CalendarAccounts(Connections connections, List<CalendarProvider> providers) {
        this.connections = Objects.requireNonNull(connections, "connections");
        Map<String, CalendarProvider> known = new LinkedHashMap<>();
        for (CalendarProvider provider : providers) {
            if (provider != null && provider.provider() != null) {
                known.putIfAbsent(provider.provider(), provider);
            }
        }
        this.providers = Map.copyOf(known);
    }

    /** The provider names that are calendars here. */
    public List<String> providers() {
        return List.copyOf(providers.keySet());
    }

    /** What {@code user} has attached, one entry per connection. */
    public List<ConnectedCalendar> of(UserId user) {
        List<ConnectedCalendar> accounts = new ArrayList<>();
        for (String provider : providers.keySet()) {
            for (ToolConnection connection : connections.of(user, provider)) {
                accounts.add(new ConnectedCalendar(provider, connection.key(),
                        connection.label(), connection.usable()));
            }
        }
        return accounts;
    }

    /** The one with this id, or empty when the user has no such account. */
    public Optional<ConnectedCalendar> find(UserId user, String accountId) {
        return of(user).stream().filter(c -> c.id().equals(accountId)).findFirst();
    }

    /** The calendars of {@code accountId}, open. The caller closes them. */
    public CalendarStore open(UserId user, String accountId) {
        String provider = ConnectedCalendar.providerOf(accountId);
        String key = ConnectedCalendar.keyOf(accountId);
        if (provider == null || key == null) {
            throw new CalendarStoreException("That is not a calendar account of yours.");
        }
        CalendarProvider opens = providers.get(provider);
        if (opens == null) {
            throw new CalendarStoreException("\"" + provider + "\" is not a calendar this installation can open.");
        }
        ToolConnection connection = connections.resolve(user, provider, key)
                .orElseThrow(() -> new CalendarStoreException(
                        "That calendar is not connected any more. Attach it again under "
                                + "Connections in your profile."));
        return opens.open(connection);
    }
}
