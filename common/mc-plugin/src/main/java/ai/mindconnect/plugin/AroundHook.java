package ai.mindconnect.plugin;

import java.util.function.Function;

/**
 * Wraps what a {@link HookPoint} is for — the work itself, not the hooks
 * before and after it. For what needs both sides at once: measuring how
 * long it took, trying again, answering from a cache. Not calling
 * {@code next} replaces the work.
 */
@FunctionalInterface
public interface AroundHook<C, R> {

    R around(C context, Function<C, R> next);
}
