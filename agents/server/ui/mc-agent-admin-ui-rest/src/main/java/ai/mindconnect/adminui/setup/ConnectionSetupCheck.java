package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The check behind "you have not connected a mailbox yet": one notice per
 * thing the installed tools ask to be connected that this user has nothing
 * attached for.
 *
 * <p>One per provider, not per tool: five mail tools share one mailbox, and
 * five notices about the same missing account would be five times the same
 * request. The key carries the provider, so the notice disappears by itself
 * as soon as anything is attached.
 */
@Component
public class ConnectionSetupCheck implements SetupCheck {

    /** Every key this check raises: {@code setup.connection.email}. */
    public static final String KEY_PREFIX = "setup.connection.";

    /** Where the user attaches one. */
    static final String PROFILE = "/admin/profile";

    private final ToolConnections connections;

    public ConnectionSetupCheck(ToolConnections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public String keyPrefix() {
        return KEY_PREFIX;
    }

    @Override
    public List<Notification.Draft> run(UserId user) {
        if (!connections.available()) {
            return List.of();               // nowhere to attach one; asking would be cruel
        }
        return connections.specs().stream()
                .filter(spec -> connections.of(user, spec.provider()).isEmpty())
                .map(ConnectionSetupCheck::draft)
                .toList();
    }

    static Notification.Draft draft(ConnectionSpec spec) {
        StringBuilder body = new StringBuilder("The ")
                .append(spec.provider()).append(" tools run on an account of your own, and you have not ")
                .append("attached one yet.");
        if (spec.description() != null && !spec.description().isBlank()) {
            body.append(' ').append(spec.description().strip());
            if (!spec.description().strip().endsWith(".")) body.append('.');
        }
        body.append(" Nobody else can see it, and a password is stored encrypted.");
        return new Notification.Draft(
                KEY_PREFIX + spec.provider(),
                NotificationLevel.ACTION_REQUIRED,
                "Still to connect: " + spec.title(),
                body.toString(),
                "Connect it",
                PROFILE);
    }
}
