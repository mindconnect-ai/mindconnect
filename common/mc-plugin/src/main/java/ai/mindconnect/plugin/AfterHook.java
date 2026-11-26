package ai.mindconnect.plugin;

/**
 * Steps in after what a {@link HookPoint} is for has happened. It sees what
 * went in and what came out and returns what comes out from here on — the
 * same result, or a changed one. Not called for a run a before-hook
 * stopped: nothing happened that it could be after.
 */
@FunctionalInterface
public interface AfterHook<C, R> {

    R after(C context, R result);
}
