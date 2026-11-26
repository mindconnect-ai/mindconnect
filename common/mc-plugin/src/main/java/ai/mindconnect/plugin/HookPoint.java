package ai.mindconnect.plugin;

/**
 * A named place where plugins may step in. Any module declares its own as
 * constants; the registry keeps no list of them.
 *
 * @param <C> what goes into the point
 * @param <R> what comes out of it
 */
public record HookPoint<C, R>(String name) {

    public static <C, R> HookPoint<C, R> of(String name) {
        return new HookPoint<>(name);
    }
}
