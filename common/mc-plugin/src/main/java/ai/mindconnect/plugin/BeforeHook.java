package ai.mindconnect.plugin;

/**
 * Steps in before what a {@link HookPoint} is for happens. It sees what goes
 * in and may let it pass, change it, or stop the run with a result of its
 * own — where a permission is checked, an approval asked, an input
 * rewritten.
 */
@FunctionalInterface
public interface BeforeHook<C, R> {

    BeforeOutcome<C, R> before(C context);
}
