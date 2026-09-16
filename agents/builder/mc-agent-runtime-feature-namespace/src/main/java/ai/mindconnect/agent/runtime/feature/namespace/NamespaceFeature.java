package ai.mindconnect.agent.runtime.feature.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.service.task.ScopeTaskAdvisor;
import ai.mindconnect.taskqueue.TaskAdvisor;

/**
 * The namespace of the current call instead of one for the runtime's life
 * (concept 30). Three things change, and nothing else: the runtime's
 * {@link ScopeSupplier} is a {@link ThreadBoundScope} the host binds per
 * request; every repository a feature registers is {@link NamespaceRouting
 * routed} to the adapter of the namespace that scope names; and every task
 * on the queue carries the scope it was submitted in and runs bound to it
 * ({@link ScopeTaskAdvisor}).
 *
 * <p>Installed explicitly — by a server that binds the scope, never found on
 * the classpath — because a runtime with it and nobody binding the scope has
 * no namespace to work in: {@link #strict()} then refuses the call,
 * {@link #fallback(Namespace)} answers with the given namespace.
 */
public class NamespaceFeature extends ConfigurableFeature {

    private ThreadBoundScope scope;

    /** The host's own scope holder — the one its request filter binds. */
    public NamespaceFeature scope(ThreadBoundScope scope) {
        changing();
        this.scope = scope;
        return this;
    }

    /** A scope of this runtime's own that refuses an unbound call (the default). */
    public NamespaceFeature strict() {
        return scope(ThreadBoundScope.strict());
    }

    /** A scope of this runtime's own that answers an unbound call with {@code namespace}. */
    public NamespaceFeature fallback(Namespace namespace) {
        return scope(ThreadBoundScope.withFallback(Scope.of(namespace)));
    }

    /** The scope holder this runtime binds — to bind it from outside: {@code scope().runIn(Scope.of(ns), …)}. */
    public ThreadBoundScope scope() {
        return scope;
    }

    @Override
    public String name() {
        return "namespace";
    }

    @Override
    protected void install(FeatureContext ctx) {
        if (scope == null) scope = ThreadBoundScope.strict();
        ThreadBoundScope bound = scope;
        ctx.instance(ScopeSupplier.class, bound);
        ctx.instance(NamespaceRouting.class, NamespaceRouting.perScope(bound));
        // Stamps the namespace on submit, binds it around every execution — first delivery, retry, wake-up.
        ctx.contribute(TaskAdvisor.class, new ScopeTaskAdvisor(bound, bound));
    }
}
