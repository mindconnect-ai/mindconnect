package ai.mindconnect.mail;

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
 * The mailboxes one user has attached, and how to open one.
 *
 * <p>Which kinds of mailbox exist is not decided here: every
 * {@link MailProvider} on the classpath brings one ({@code ServiceLoader}), and
 * a host may hand in its own. With the IMAP module alone that is IMAP and
 * POP3; with the Microsoft and Google modules beside it, Outlook and Gmail
 * appear under the same ids and the same tools.
 *
 * <p>A mailbox is named {@code provider.key} — {@code email.work},
 * {@code microsoft.office} — as the screens and the tools name it.
 */
public class MailAccounts {

    private final Connections connections;
    private final Map<String, MailProvider> providers;

    /** Every provider the classpath brings. */
    public MailAccounts(Connections connections) {
        this(connections, ServiceLoader.load(MailProvider.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** The providers given — for a host that builds them itself, and for tests. */
    public MailAccounts(Connections connections, List<MailProvider> providers) {
        this.connections = Objects.requireNonNull(connections, "connections");
        Map<String, MailProvider> known = new LinkedHashMap<>();
        for (MailProvider provider : providers) {
            if (provider != null && provider.provider() != null) {
                known.putIfAbsent(provider.provider(), provider);
            }
        }
        this.providers = Map.copyOf(known);
    }

    /** The provider names that are mailboxes here — what {@link #of} looks for. */
    public List<String> providers() {
        return List.copyOf(providers.keySet());
    }

    /** What {@code user} has attached, one entry per connection. */
    public List<ConnectedMailbox> of(UserId user) {
        List<ConnectedMailbox> mailboxes = new ArrayList<>();
        for (String provider : providers.keySet()) {
            for (ToolConnection connection : connections.of(user, provider)) {
                mailboxes.add(new ConnectedMailbox(provider, connection.key(),
                        connection.label(), connection.usable()));
            }
        }
        return mailboxes;
    }

    /** The one with this id, or empty when the user has no such mailbox. */
    public Optional<ConnectedMailbox> find(UserId user, String mailboxId) {
        return of(user).stream().filter(m -> m.id().equals(mailboxId)).findFirst();
    }

    /**
     * The mailbox {@code mailboxId} names, open. The caller closes it; nothing
     * is pooled, because a pool would have to stay honest across a password
     * change and a server that drops idle connections.
     */
    public MailStore open(UserId user, String mailboxId) {
        String provider = ConnectedMailbox.providerOf(mailboxId);
        String key = ConnectedMailbox.keyOf(mailboxId);
        if (provider == null || key == null) {
            throw new MailStoreException("That is not a mailbox of yours.");
        }
        MailProvider opens = providers.get(provider);
        if (opens == null) {
            throw new MailStoreException("\"" + provider + "\" is not a mailbox this installation can open.");
        }
        ToolConnection connection = connections.resolve(user, provider, key)
                .orElseThrow(() -> new MailStoreException(
                        "That mailbox is not connected any more. Attach it again under "
                                + "Connections in your profile."));
        return opens.open(connection);
    }
}
