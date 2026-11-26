package ai.mindconnect.agent.runtime.engine.service;

import java.util.function.Function;

import ai.mindconnect.plugin.HookPoint;

/**
 * Runs work at a hook point. The engine's classes get this and not the
 * plugin registry: they may let others take part in what they do, they may
 * not look services up.
 */
@FunctionalInterface
interface HookRunner {

    <C, R> R run(HookPoint<C, R> point, C context, Function<C, R> work);
}
