package ai.mindconnect.agent.starter.runtime.engine;

import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.UserProvider;

/**
 * Who acts is the user of the application's scope: the one signed in for
 * the request. On a worker of the queue the runtime binds that user again
 * through the same scope supplier, in the namespace already bound there, so
 * the application's stores answer for them — a user's own variables, the
 * accounts a user attached.
 */
final class ScopeUserProvider implements UserProvider {

    private final ScopeSupplier scope;

    ScopeUserProvider(ScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    public Optional<UserId> current() {
        return scope.get().userIfAny();
    }

    @Override
    public <T> T callAs(UserId user, Supplier<T> body) {
        try {
            return scope.callIn(Scope.of(scope.namespace(), user), body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A Supplier throws nothing checked; only the scope supplier's own signature allows it.
            throw new IllegalStateException(e);
        }
    }
}
