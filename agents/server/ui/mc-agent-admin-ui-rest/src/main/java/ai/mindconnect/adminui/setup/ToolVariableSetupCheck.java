package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolVariable;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The check behind "your mailbox is not configured yet": every required
 * variable an installed tool declares that this user has no value for becomes
 * one notice, pointing at the place where they can type it.
 *
 * <p>One notice per variable rather than one per tool, because that is what
 * the user acts on: clearing a notice is filling in a field, and a single
 * "the mail tools are not configured" would stay there while three of four
 * values are already in. The key carries the variable's name, so the notice
 * disappears by itself as soon as the value exists.
 *
 * <p>It also {@linkplain ToolVariables#provision provisions} what can be
 * provisioned first: a variable with a sensible default is written rather than
 * asked about, so the user is only ever shown what nobody could have guessed
 * for them — host, account, password.
 */
@Component
public class ToolVariableSetupCheck implements SetupCheck {

    /** Every key this check raises: {@code setup.tool-variable.MC_EMAIL_HOST}. */
    public static final String KEY_PREFIX = "setup.tool-variable.";

    /** Where the user fills them in — the profile's own variables. */
    static final String PROFILE_VARIABLES = "/admin/profile";

    private final ToolVariables variables;

    public ToolVariableSetupCheck(ToolVariables variables) {
        this.variables = Objects.requireNonNull(variables, "variables");
    }

    @Override
    public String keyPrefix() {
        return KEY_PREFIX;
    }

    @Override
    public List<Notification.Draft> run(UserId user) {
        variables.provision(user);
        return variables.missingRequired(user).stream().map(ToolVariableSetupCheck::draft).toList();
    }

    /** The notice for one missing variable. */
    static Notification.Draft draft(ToolVariable variable) {
        String source = variable.declaredBy() == null ? "A tool" : "The " + variable.declaredBy() + " tools";
        StringBuilder body = new StringBuilder(source)
                .append(" need ").append(variable.title()).append(" before they can run for you.");
        if (variable.description() != null && !variable.description().isBlank()) {
            body.append(' ').append(variable.description().strip());
            if (!variable.description().strip().endsWith(".")) body.append('.');
        }
        body.append(" Set it as your variable ").append(variable.name())
                .append(" — nobody else sees it, and it is stored encrypted.");
        return new Notification.Draft(
                KEY_PREFIX + variable.name(),
                NotificationLevel.ACTION_REQUIRED,
                "Still to set up: " + variable.title(),
                body.toString(),
                "Set it up",
                PROFILE_VARIABLES);
    }
}
