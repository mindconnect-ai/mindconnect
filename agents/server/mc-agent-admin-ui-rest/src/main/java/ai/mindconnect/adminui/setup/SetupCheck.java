package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;

import java.util.List;

/**
 * One thing that can still be missing for a user, looked at when they sign in.
 *
 * <p>An installation is rarely finished the moment somebody's account exists:
 * a tool wants a mailbox configured, a provider wants a key of their own. The
 * user cannot be expected to guess any of it, and an error in the middle of a
 * chat is a bad place to find out. A check turns that into a notice waiting
 * for them.
 *
 * <p>Implement it as a Spring bean; {@link UserSetup} collects every one of
 * them and runs them on the first request of a session. A check is
 * <b>idempotent by construction</b>: it says what is missing <em>now</em>, and
 * the service behind it collapses a repeated answer onto the entry the user
 * already has. Whatever it stops raising is cleared — so a check must return
 * its whole current answer every time, not the difference since last time.
 *
 * <p>Keep it cheap. It runs on a request thread, once per sign-in, in front of
 * a page the user is waiting for.
 */
public interface SetupCheck {

    /**
     * The prefix every key this check raises starts with — {@code "setup.tool-variable."}.
     * It is how {@link UserSetup} tells this check's entries from another's when
     * it clears what is no longer missing, so it must be this check's alone.
     */
    String keyPrefix();

    /**
     * What is missing for {@code user} right now; empty when they are set up.
     * Every draft's key starts with {@link #keyPrefix()}.
     *
     * <p>A check may put right what it can put right by itself — writing a
     * variable that has a sensible default, say — and report only what is
     * genuinely left for the user to do.
     */
    List<Notification.Draft> run(UserId user);
}
